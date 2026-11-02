"""Tests for `notify/`: the two channel APIs and what becomes a message."""
import asyncio
import json
import logging
import time

import aiohttp
import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from petkit_local.devices.registry import DeviceRegistry
from petkit_local.devices.ble import BLERegistry
from petkit_local.notify import channels
from petkit_local.notify.channels import Message, NotifyError
from petkit_local.notify.notifier import (
    ERROR_REPEAT_SEC, QUEUE_SIZE, TEST_INTERVAL_SEC, Notifier, topic_of,
)
from petkit_local.web.api.settings import LIVE_SETTING_DEFAULTS, LIVE_SETTINGS, SECRET_SET
from petkit_local.web.hub import EventHub
from petkit_local.web.panel import create_panel_app

MESSAGE = Message("标题", "正文")
SPUG = "https://push.spug.cc/xsend/abc123"
WECOM = "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=693a-91f6"


class Service:
    """A local stand-in for a notification API, recording what it was sent."""

    def __init__(self, answer=None, status=200):
        self.answer, self.status, self.bodies = answer, status, []

    async def __aenter__(self):
        app = web.Application()
        app.router.add_post("/{path:.*}", self._handle)
        self.server = TestServer(app)
        await self.server.start_server()
        self.session = aiohttp.ClientSession()
        return self

    async def __aexit__(self, *exc):
        await self.session.close()
        await self.server.close()

    async def _handle(self, request):
        self.bodies.append(await request.json())
        return web.json_response(self.answer or {}, status=self.status)

    def url(self, path):
        return str(self.server.make_url(path))


class FakeDevice:
    petkit_id = 7
    device_type = "t6"
    serial_number = "20240001ABCD"
    last_heartbeat = last_state_report = last_seen = last_mqtt = 0.0


class FakeRegistry:
    def __init__(self, devices=()):
        self.devices = list(devices)

    def all(self):
        return self.devices


def _live(**over):
    return {"notify_spug_url": SPUG, "notify_error": True, "notify_toilet": True,
            "notify_feeding": True, "notify_cleaning": True, "notify_offline": True, **over}


def _queued(notifier):
    return notifier._queue.qsize()


def _visit(event_id="e1", event_type="10", **content):
    return {"event_uid": f"{event_id}:{event_type}", "related_event": event_id,
            "event_type": event_type, "device_type": "t6", "ts": time.time(),
            "content_json": json.dumps(content)}


# --- the channels ---

async def test_xsend_posts_the_message_and_the_channel():
    async with Service({"code": 200}) as s:
        await channels.send_spug(s.session, s.url("/xsend/abc"), "wx_mp", MESSAGE)
    assert s.bodies == [{"title": "标题", "content": "正文", "channel": "wx_mp"}]


async def test_a_template_address_fills_key1_and_key2():
    async with Service({"code": 200}) as s:
        await channels.send_spug(s.session, s.url("/send/abc"), "wx_mp", MESSAGE)
    assert s.bodies == [{"key1": "标题", "key2": "正文"}]


async def test_spug_refusal_is_an_error():
    async with Service({"code": 400, "msg": "bad template"}) as s:
        with pytest.raises(NotifyError, match="bad template"):
            await channels.send_spug(s.session, s.url("/xsend/abc"), "", MESSAGE)


async def test_wecom_sends_text_and_cuts_on_a_character_boundary():
    async with Service({"errcode": 0}) as s:
        await channels.send_wecom(s.session, s.url("/x"), Message("t", "猫" * 2000))
    content = s.bodies[0]["text"]["content"]
    assert s.bodies[0]["msgtype"] == "text"
    assert len(content.encode()) <= channels.WECOM_TEXT_LIMIT
    assert content.endswith("猫")


async def test_wecom_errcode_is_an_error():
    async with Service({"errcode": 45009, "errmsg": "freq limit"}) as s:
        with pytest.raises(NotifyError, match="45009"):
            await channels.send_wecom(s.session, s.url("/x"), MESSAGE)


async def test_one_failing_channel_does_not_stop_the_other_nor_leak_its_address():
    async with Service({"errcode": 0}) as s:
        live = {"notify_spug_url": "http://127.0.0.1:1/xsend/SECRET",
                "notify_wecom_url": s.url("/x")}
        results = await channels.deliver(s.session, live, MESSAGE)
    assert results["wecom"] == ""
    assert results["spug"] and "SECRET" not in results["spug"]


@pytest.mark.parametrize("url,ok", [
    ("", True),
    (SPUG, True),
    ("https://push.spug.cc/send/abc", True),
    ("http://push.spug.cc/send/abc", False),
    ("https://evil.example/send/abc", False),
    ("https://push.spug.cc.evil.example/send/abc", False),
    ("https://push.spug.cc/send/abc?x=https://evil", False),
])
def test_spug_addresses(url, ok):
    assert channels.valid_spug_url(url) is ok


@pytest.mark.parametrize("url,ok", [
    (WECOM, True),
    ("https://evil.example/cgi-bin/webhook/send?key=a", False),
    ("https://qyapi.weixin.qq.com/cgi-bin/webhook/send", False),
])
def test_wecom_addresses(url, ok):
    assert channels.valid_wecom_url(url) is ok


# --- what is worth a message ---

@pytest.mark.parametrize("event_type,device_type,topic", [
    ("10", "t6", "toilet"),
    ("pet_out", "t6", "toilet"),
    ("9", "t5", None),          # the mid-visit sample of the same visit
    ("pet_in", "t6", None),
    ("20", "t6", None),         # petEvent alone is not a toilet visit
    ("error_start", "t6", "error"),
    ("error_over", "t6", None),
    ("feed_over", "d4sh", "feeding"),
    ("eat_over", "d4sh", None),
    ("2", "d4h", "feeding"),    # the same code is err_over on a litter box
    ("2", "t6", None),
    ("clean_over", "t6", "cleaning"),
    ("deodorant_over", "t6", None),
    ("nonsense", "t6", None),
])
def test_topic_of(event_type, device_type, topic):
    assert topic_of({"event_type": event_type, "device_type": device_type}) == topic


def test_a_visit_is_sent_once_however_often_the_device_repeats_it():
    n = Notifier(_live(), FakeRegistry())
    n.on_event(FakeDevice(), _visit())
    n.on_event(FakeDevice(), _visit())
    n.on_event(FakeDevice(), _visit("e2"))
    assert _queued(n) == 2


def test_nothing_is_queued_when_the_topic_is_off_or_no_channel_is_set():
    off = Notifier(_live(notify_toilet=False), FakeRegistry())
    off.on_event(FakeDevice(), _visit())
    empty = Notifier(_live(notify_spug_url=""), FakeRegistry())
    empty.on_event(FakeDevice(), _visit())
    assert _queued(off) == _queued(empty) == 0


def test_a_flapping_error_is_held_back_until_the_window_passes():
    n = Notifier(_live(), FakeRegistry())
    row = _visit("a", "error_start", err="full")
    n.on_event(FakeDevice(), row)
    n.on_event(FakeDevice(), {**row, "related_event": "b", "event_uid": "b"})
    assert _queued(n) == 1
    for key in n._seen:
        n._seen[key] -= ERROR_REPEAT_SEC + 1
    n.on_event(FakeDevice(), row)
    assert _queued(n) == 2


def test_a_full_queue_drops_rather_than_blocks(caplog):
    n = Notifier(_live(), FakeRegistry())
    with caplog.at_level(logging.WARNING):
        for i in range(QUEUE_SIZE + 1):
            n.on_event(FakeDevice(), _visit(f"e{i}"))
    assert _queued(n) == QUEUE_SIZE
    assert "queue full" in caplog.text


def test_a_malformed_row_never_raises_into_the_device_path():
    n = Notifier(_live(), FakeRegistry())
    n.on_event(FakeDevice(), {"event_type": 10, "content_json": "{not json"})
    n.on_event(None, _visit())


async def test_the_toilet_message_names_the_pet_and_reports_the_visit():
    class Pets:
        async def get(self, pet_id):
            return {"name": "咪咪"}

    n = Notifier(_live(), FakeRegistry(), Pets())
    row = {**_visit(pet_weight=4210, time_in=100, time_out=145), "pet_id": 1}
    message = await n._render("toilet", row, "T6 ABCD 如厕记录")
    assert "宠物：咪咪" in message.body
    assert "体重：4.21 kg" in message.body
    assert "时长：45 秒" in message.body


async def test_the_worker_delivers_a_queued_event():
    async with Service({"code": 200}) as s:
        n = Notifier(_live(notify_spug_url=s.url("/xsend/abc")), FakeRegistry())
        task = asyncio.create_task(n.run())
        n.on_event(FakeDevice(), _visit())
        for _ in range(100):
            if s.bodies:
                break
            await asyncio.sleep(0.01)
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
    assert s.bodies[0]["title"] == "T6 ABCD 如厕记录"


async def test_offline_is_announced_on_the_transition_only():
    device = FakeDevice()
    device.last_heartbeat = time.time()
    n = Notifier(_live(), FakeRegistry([device]))
    task = asyncio.create_task(n.watch_offline(100, interval=0.01))

    async def titles(count):
        got = []
        for _ in range(count):
            got.append((await asyncio.wait_for(n._queue.get(), 1))[2])
        return got

    await asyncio.sleep(0.03)
    assert _queued(n) == 0
    device.last_heartbeat = time.time() - 1000
    assert await titles(1) == ["T6 ABCD 设备离线"]
    await asyncio.sleep(0.03)
    assert _queued(n) == 0
    device.last_heartbeat = time.time()
    assert await titles(1) == ["T6 ABCD 设备恢复在线"]
    task.cancel()
    await asyncio.gather(task, return_exceptions=True)


async def test_a_device_that_never_returns_after_a_restart_is_announced():
    """Contact times are not persisted, so a box that died across a restart
    reads as never seen; it must still be reported once `timeout` passes."""
    device = FakeDevice()
    n = Notifier(_live(), FakeRegistry([device]))
    task = asyncio.create_task(n.watch_offline(0.05, interval=0.01))
    try:
        title = (await asyncio.wait_for(n._queue.get(), 1))[2]
        assert title == "T6 ABCD 设备离线"
        device.last_heartbeat = time.time()
        title = (await asyncio.wait_for(n._queue.get(), 1))[2]
        assert title == "T6 ABCD 设备恢复在线"
    finally:
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)


async def test_a_device_seen_soon_after_a_restart_is_not_announced():
    device = FakeDevice()
    n = Notifier(_live(), FakeRegistry([device]))
    task = asyncio.create_task(n.watch_offline(0.2, interval=0.01))
    await asyncio.sleep(0.03)
    for _ in range(30):  # well past the timeout, polling like a live device
        device.last_heartbeat = time.time()
        await asyncio.sleep(0.01)
    task.cancel()
    await asyncio.gather(task, return_exceptions=True)
    assert _queued(n) == 0


async def test_a_device_quiet_at_startup_is_not_news():
    device = FakeDevice()
    device.last_heartbeat = time.time() - 1000
    n = Notifier(_live(), FakeRegistry([device]))
    task = asyncio.create_task(n.watch_offline(100, interval=0.01))
    await asyncio.sleep(0.03)
    task.cancel()
    await asyncio.gather(task, return_exceptions=True)
    assert _queued(n) == 0


# --- the panel ---

async def _client(live, notifier=None, tmp=None):
    cfg = {"api_url": "http://x/6/", "capture": False, "capture_dir": "/nope"}
    if tmp:
        cfg["settings_path"] = str(tmp / "overrides.json")
    app = create_panel_app(DeviceRegistry(), BLERegistry(), EventHub(), cfg, None, live_config=live)
    app["notifier"] = notifier
    client = TestClient(TestServer(app))
    await client.start_server()
    return client


async def test_a_saved_address_never_comes_back_nor_reaches_the_log(tmp_path, caplog):
    live = dict(LIVE_SETTING_DEFAULTS)
    c = await _client(live, tmp=tmp_path)
    try:
        with caplog.at_level(logging.INFO):
            r = await c.post("/api/settings", json={"notify_wecom_url": f" {WECOM} "})
        text = await r.text()
        assert r.status == 200 and "693a" not in text
        assert live["notify_wecom_url"] == WECOM
        assert json.loads((tmp_path / "overrides.json").read_text())["notify_wecom_url"] == WECOM
        assert "693a" not in caplog.text
        s = (await (await c.get("/api/settings")).json())["settings"]
        assert s["notify_wecom_url"] == SECRET_SET
        assert "693a" not in await (await c.get("/api/info")).text()
    finally:
        await c.close()


async def test_an_address_off_the_service_host_is_refused_and_nothing_is_applied():
    live = dict(LIVE_SETTING_DEFAULTS)
    c = await _client(live)
    try:
        r = await c.post("/api/settings", json={"capture": True,
                                                "notify_spug_url": "https://evil.example/send/a"})
        assert r.status == 400
        assert live["notify_spug_url"] == "" and live["capture"] is False
    finally:
        await c.close()


async def test_an_empty_value_clears_a_saved_address():
    live = dict(LIVE_SETTING_DEFAULTS, notify_spug_url=SPUG)
    c = await _client(live)
    try:
        assert (await c.post("/api/settings", json={"notify_spug_url": ""})).status == 200
        assert live["notify_spug_url"] == ""
    finally:
        await c.close()


async def test_test_button():
    async with Service({"code": 200}) as s:
        live = dict(LIVE_SETTING_DEFAULTS, notify_spug_url=s.url("/xsend/abc"))
        notifier = Notifier(live, FakeRegistry())
        c = await _client(live, notifier)
        try:
            first = await (await c.post("/api/notify/test")).json()
            assert first == {"ok": True, "results": {"spug": ""}}
            assert (await c.post("/api/notify/test")).status == 429
            notifier._last_test -= TEST_INTERVAL_SEC + 1
            assert (await c.post("/api/notify/test")).status == 200
            assert len(s.bodies) == 2
        finally:
            await c.close()
            await notifier._session.close()


async def test_test_button_with_nothing_configured():
    live = dict(LIVE_SETTING_DEFAULTS)
    c = await _client(live, Notifier(live, FakeRegistry()))
    try:
        assert (await c.post("/api/notify/test")).status == 400
    finally:
        await c.close()


def test_every_credential_setting_is_a_live_setting():
    assert {"notify_spug_url", "notify_wecom_url"} <= set(LIVE_SETTINGS)
