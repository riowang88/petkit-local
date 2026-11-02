"""Which events become a message, and delivering them outside the device's path.

`on_event` runs inside a device's HTTP request or the bridge's one `#`
subscription, so it only queues: a slow notification service must not hold a
device reply, and nothing here may raise into either. A full queue drops the
message and says so.

One visit produces several reports and a device repeats one it thinks was lost,
so a topic is sent once per episode, and an error also once per
`ERROR_REPEAT_SEC`, because a fault can flap faster than anyone can act on it.

Offline is watched here rather than taken from `ha/publisher.py`'s watchdog:
that one only runs with Home Assistant, and `--no-ha` is a supported deployment.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
from collections import OrderedDict
from datetime import datetime
from typing import TYPE_CHECKING, Any

import aiohttp

from petkit_local.events import codes, decode
from petkit_local.events.normalize import _CLEAN_DONE_WORDS
from petkit_local.ha.publisher import device_is_stale
from petkit_local.notify.channels import Message, deliver
from petkit_local.utils.coerce import to_int

if TYPE_CHECKING:  # pragma: no cover - typing only
    from petkit_local.ai.pets import PetRegistry
    from petkit_local.devices.base import Device
    from petkit_local.devices.registry import DeviceRegistry

log = logging.getLogger(__name__)

TOPIC_ERROR = "error"
TOPIC_OFFLINE = "offline"
TOPIC_TOILET = "toilet"
TOPIC_FEEDING = "feeding"
TOPIC_CLEANING = "cleaning"

TITLES = {
    TOPIC_ERROR: "设备故障",
    TOPIC_TOILET: "如厕记录",
    TOPIC_FEEDING: "喂食完成",
    TOPIC_CLEANING: "清理完成",
}

QUEUE_SIZE = 64
SEND_TIMEOUT = aiohttp.ClientTimeout(total=10)
ERROR_REPEAT_SEC = 600
#: Long enough to outlive any device retry of one episode.
EPISODE_REPEAT_SEC = 86400
SEEN_LIMIT = 512
#: A panel with no authentication must not be a way to exhaust WeCom's
#: 20-messages-a-minute allowance.
TEST_INTERVAL_SEC = 10


def topic_of(row: dict[str, Any]) -> str | None:
    """The notification topic of an event row, or None if it is not one.

    Read from the code table rather than `row["event_kind"]`: content flags can
    put a mid-visit sample in the toilet bucket, and only a visit's summary
    means the visit happened. The cleaning bucket likewise holds deodorizing and
    the illuminator, which is what `_CLEAN_DONE_WORDS` excludes.
    """
    code = codes.lookup(row.get("event_type"), row.get("device_type"))
    if code is None:
        return None
    if code.role == codes.ROLE_ERROR_START:
        return TOPIC_ERROR
    if code.kind == codes.KIND_TOILET and code.role == codes.ROLE_VISIT_SUMMARY:
        return TOPIC_TOILET
    if code.role == codes.ROLE_DONE:
        if code.kind == codes.KIND_FEEDING and code.done_word == "feeding":
            return TOPIC_FEEDING
        if code.kind == codes.KIND_CLEANING and code.done_word in _CLEAN_DONE_WORDS:
            return TOPIC_CLEANING
    return None


def _device_label(device: Device) -> str:
    """Short enough for a title; the serial tail tells two boxes apart."""
    tail = (device.serial_number or "")[-4:]
    return f"{device.device_type.upper()} {tail}".strip()


def _never_seen(device: Device) -> bool:
    """Whether this process has had no contact at all from `device`."""
    return max(device.last_heartbeat, device.last_state_report,
               device.last_seen, device.last_mqtt) <= 0


def _clock(ts: Any) -> str:
    """A wall-clock time, in this process's timezone."""
    moment = datetime.fromtimestamp(ts) if isinstance(ts, (int, float)) else datetime.now()
    return moment.strftime("%m-%d %H:%M:%S")


def _content_of(row: dict[str, Any]) -> dict[str, Any]:
    """The row's content; it was parsed once already, so this cannot be hostile."""
    try:
        content = json.loads(row.get("content_json") or "{}")
    except ValueError:
        return {}
    return content if isinstance(content, dict) else {}


def _toilet_lines(content: dict[str, Any]) -> list[str]:
    """Weight and duration, when this visit reported them."""
    lines = []
    weight = to_int(content.get("pet_weight"), 0)
    if weight > 0:
        lines.append(f"体重：{weight / 1000:.2f} kg")
    time_in, time_out = to_int(content.get("time_in"), 0), to_int(content.get("time_out"), 0)
    if time_in and time_out > time_in:
        lines.append(f"时长：{time_out - time_in} 秒")
    return lines


class Notifier:
    """Turns device events into messages on the channels in `live`."""

    def __init__(self, live: dict[str, Any], registry: DeviceRegistry,
                 pet_registry: PetRegistry | None = None) -> None:
        """Wire the collaborators; nothing is sent until `run()`.

        Args:
            live: The shared config dict, read per message so a panel change
                needs no restart.
            registry: Walked by `watch_offline`.
            pet_registry: Names the pet in a toilet message; None omits it.
        """
        self._live = live
        self._registry = registry
        self._pet_registry = pet_registry
        self._queue: asyncio.Queue[tuple[str, dict[str, Any], str]] = asyncio.Queue(QUEUE_SIZE)
        self._seen: OrderedDict[tuple[Any, ...], float] = OrderedDict()
        self._session: aiohttp.ClientSession | None = None
        self._last_test = 0.0

    def configured(self) -> bool:
        """Whether any channel has an address."""
        return bool(self._live.get("notify_spug_url") or self._live.get("notify_wecom_url"))

    def _session_now(self) -> aiohttp.ClientSession:
        """The shared session, created on the running loop at first use."""
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(timeout=SEND_TIMEOUT)
        return self._session

    def _first_time(self, key: tuple[Any, ...], window: float) -> bool:
        """Record `key`, and whether it was not already seen within `window`."""
        now = time.time()
        last = self._seen.get(key)
        if last is not None and now - last < window:
            return False
        self._seen[key] = now
        self._seen.move_to_end(key)
        while len(self._seen) > SEEN_LIMIT:
            self._seen.popitem(last=False)
        return True

    def _enqueue(self, topic: str, row: dict[str, Any], title: str) -> None:
        """Hand one message to the worker, or drop it if it has fallen behind."""
        try:
            self._queue.put_nowait((topic, row, title))
        except asyncio.QueueFull:
            log.warning("notify: queue full, dropping a %s message", topic)

    def on_event(self, device: Device, row: dict[str, Any]) -> None:
        """Queue a message if this stored event row deserves one. Never raises."""
        try:
            topic = topic_of(row)
            if topic is None or not self.configured() or not self._live.get(f"notify_{topic}"):
                return
            label = decode.event_label(row.get("event_type"), _content_of(row), device.device_type)
            if topic == TOPIC_ERROR:
                fresh = self._first_time((device.petkit_id, topic, label), ERROR_REPEAT_SEC)
            else:
                episode = row.get("related_event") or row.get("event_uid")
                fresh = episode is None or self._first_time(
                    (device.petkit_id, topic, episode), EPISODE_REPEAT_SEC)
            if fresh:
                self._enqueue(topic, {**row, "_label": label},
                              f"{_device_label(device)} {TITLES[topic]}")
        except Exception:
            log.exception("notify: could not queue an event")

    async def _render(self, topic: str, row: dict[str, Any], title: str) -> Message:
        """The message for a queued event."""
        lines = [f"时间：{_clock(row.get('ts'))}"]
        if topic == TOPIC_ERROR:
            lines.append(f"详情：{row.get('_label')}")
        elif topic == TOPIC_TOILET:
            if row.get("pet_id") is not None and self._pet_registry is not None:
                pet = await self._pet_registry.get(row["pet_id"])
                if pet:
                    lines.append(f"宠物：{pet['name']}")
            lines.extend(_toilet_lines(_content_of(row)))
        return Message(title, "\n".join(lines))

    async def run(self) -> None:
        """Deliver queued messages for the life of the process."""
        try:
            while True:
                topic, row, title = await self._queue.get()
                try:
                    message = await self._render(topic, row, title)
                    results = await deliver(self._session_now(), self._live, message)
                except Exception:
                    log.exception("notify: delivery failed")
                    continue
                for name, error in results.items():
                    if error:
                        log.warning("notify: %s refused a %s message: %s", name, topic, error)
        finally:
            if self._session is not None:
                await self._session.close()

    async def watch_offline(self, timeout: int, interval: float | None = None) -> None:
        """Announce each device going quiet and coming back.

        Devices already quiet at startup are the baseline, not news: every
        restart would otherwise report them. Tracked whether or not the topic is
        on, so switching it on does not announce an old outage.

        Contact times are not persisted, so after a restart every device reads
        as never seen, which `device_is_stale` does not count as stale. One
        that stays unseen for `timeout` after we started is counted as quiet
        here, or a device that died across a restart would never be reported.
        """
        if interval is None:
            interval = max(15.0, min(30.0, timeout / 2))
        started = time.time()

        def quiet(device: Device, now: float) -> bool:
            if _never_seen(device):
                return now - started > timeout
            return device_is_stale(device, now, timeout)

        stale = {d.petkit_id for d in self._registry.all() if quiet(d, started)}
        while True:
            await asyncio.sleep(interval)
            now = time.time()
            for device in self._registry.all():
                is_stale = quiet(device, now)
                if is_stale == (device.petkit_id in stale):
                    continue
                (stale.add if is_stale else stale.discard)(device.petkit_id)
                if self.configured() and self._live.get(f"notify_{TOPIC_OFFLINE}"):
                    word = "设备离线" if is_stale else "设备恢复在线"
                    self._enqueue(TOPIC_OFFLINE, {"ts": now}, f"{_device_label(device)} {word}")

    async def send_test(self) -> dict[str, str] | None:
        """Send a test message now.

        Returns:
            `deliver`'s results, or None if the last test was too recent.
        """
        now = time.time()
        if now - self._last_test < TEST_INTERVAL_SEC:
            return None
        self._last_test = now
        return await deliver(self._session_now(), self._live,
                             Message("PetKit Local 测试通知", f"时间：{_clock(now)}"))
