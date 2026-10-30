"""go2rtc as a sidecar: the only safe way to hand a device camera to anything.

The camera patcher gets H.264 out of the device, and then nothing can consume it.
Two faults, both measured on a T5:

* **Home Assistant's Generic Camera segfaults on the FLV.** Its `stream`
  component opens the URL with PyAV and `av.open()` dies inside libav, killing
  the whole Python process — `Fatal Python error: Segmentation fault` in
  `stream/worker.py::try_open_stream`, and HA restarts. Seen twice, with audio
  and without, and NOT reproducible with `av.open` in isolation (single, looped,
  three-concurrent, or with HA's own options), so it needs HA's threading
  context. That makes it an HA/PyAV bug we cannot fix and have to route around.
* **tserver needs seconds between connections.** Opened back to back it refuses
  the second one; the same pair six seconds apart both succeed. Three concurrent
  opens serve one and refuse two. So it is a cooldown, not an alternation — and
  anything that opens a stream per viewer runs into it.

go2rtc fixes both: it reads the FLV, republishes RTSP, and holds exactly ONE
connection to the device however many consumers attach. Verified end to end,
including a 120 s soak with no reconnects.

It does not abolish the cooldown, and cannot: go2rtc drops the producer when the
last consumer leaves, so a viewer arriving immediately after another left still
lands in it and gets a 404. Measured on the deployed add-on — back-to-back opens
fail, six seconds apart all succeed. Normal viewing does not look like that, and
the alternative is holding the device streaming 24/7, which is the thing this
design exists to avoid.

It is deliberately a child process rather than a library. go2rtc is Go, it is
already the thing Home Assistant itself ships for WebRTC, and re-implementing an
RTSP server in Python to avoid one `exec` would be the worse trade.

**The device is only streamed from while someone is watching.** go2rtc dials the
producer on the first consumer and drops it when the last one leaves, which is
why this is a sidecar and not a frame pump — a device sitting at 82% CPU should
not stream 24/7 to fill a thumbnail.
"""
from __future__ import annotations

import asyncio
import contextlib
import logging
import os
import shutil
import socket
import time
from typing import TYPE_CHECKING, Any, NamedTuple

import aiohttp

from petkit_local.patchers.camera import STREAM_PATHS, stream_urls

if TYPE_CHECKING:  # pragma: no cover - typing only
    from petkit_local.devices.base import Device
    from petkit_local.devices.registry import DeviceRegistry

log = logging.getLogger(__name__)

#: Where go2rtc listens. RTSP is the only one anything else talks to, and under
#: the Supervisor it does not need a host port at all — Home Assistant reaches
#: add-ons by hostname on the internal network. The API is bound to loopback
#: because nothing outside this container has any business calling it, and
#: WebRTC is off entirely: an RTSP source does not need it, and its UDP
#: behaviour behind container NAT is a problem we would be buying for nothing.
RTSP_PORT = 8554
API_ADDR = "127.0.0.1:1984"

#: How often the supervisor reconciles. Same shape and the same reasoning as
#: `mqtt/upstream.py`: the panel's only contract is that it mutates shared
#: state, so this is polled rather than subscribed to.
SUPERVISE_INTERVAL_SECONDS = 30.0

#: How long a probe result is trusted. Long on purpose — see `probe_stream`.
PROBE_TTL_SECONDS = 600.0
# A reboot/cooldown must not hide a recovered camera for ten minutes.
PROBE_RETRY_SECONDS = 60.0

#: The first bytes of an FLV file. An open port is NOT evidence of a stream, so
#: this signature is what the probe actually requires.
FLV_SIGNATURE = b"FLV\x01"

#: Give up on a probe quickly: it runs against a device on the LAN, and a slow
#: answer is as good as no answer for a question this cheap.
PROBE_TIMEOUT = 5.0

#: `state` key holding the probe's verdict. Derived, like `lastClipPath` and
#: `streamUrl`, and deliberately in `state` rather than `config`: it describes
#: the device as it is right now, and it must not survive a restart as a fact.
STREAM_AVAILABLE = "streamAvailable"

#: Why a probe said what it said. `streamAvailable` going missing used to be the
#: whole story, and it hid the one distinction that matters for a fix: a device
#: we cannot reach at all (`unreachable`, `timeout` — the network) versus
#: one that answers but serves no stream (`http_<status>`, `bad_signature` — the
#: patch, or tserver). Codes rather than prose, because the panel translates them.
REASON_OK = "ok"
REASON_NO_IP = "no_ip"
REASON_UNREACHABLE = "unreachable"
REASON_TIMEOUT = "timeout"
REASON_BAD_SIGNATURE = "bad_signature"
REASON_ERROR = "error"

_have_go2rtc_cache: bool | None = None


def have_go2rtc() -> bool:
    """Whether the go2rtc binary is on PATH.

    Resolved once per process, like `transcode.have_ffmpeg`: the answer cannot
    change inside a container's lifetime, and this is asked on every pass.
    """
    global _have_go2rtc_cache
    if _have_go2rtc_cache is None:
        _have_go2rtc_cache = shutil.which("go2rtc") is not None
    return _have_go2rtc_cache


def stream_name(device: Device) -> str:
    """go2rtc's name for this device's stream — the RTSP path.

    The petkit id, because it is the one identifier that is stable, unique and
    already how every other topic and route addresses a device. A friendly name
    would be nicer to read and would change under the owner's hands.
    """
    return str(device.petkit_id)


def rtsp_url(device: Device, host: str) -> str:
    """The address to hand Home Assistant for this device."""
    return f"rtsp://{host}:{RTSP_PORT}/{stream_name(device)}"


def advertised_host() -> str:
    """The hostname to put in an RTSP URL, as seen from outside this container.

    Read at runtime and never hardcoded, because the name is not ours to predict:
    the Supervisor prefixes an add-on with its REPOSITORY, not its slug. A local
    install is `local-<slug>`; published, it becomes `<url-hash>-<slug>`. So the
    only source that is right on every install is the container itself.

    Falls back to the LAN address when the hostname does not resolve — the
    docker-compose path, which has no internal DNS.
    """
    name = socket.gethostname()
    try:
        socket.getaddrinfo(name, None)
    except OSError:
        return ""
    return name


class ProbeResult(NamedTuple):
    """A probe's verdict and the reason for it — one of the `REASON_*` codes, or
    `http_<status>` for a device that answered with something other than 200."""

    ok: bool
    reason: str


async def probe_stream(ip: str, timeout: float = PROBE_TIMEOUT) -> bool:
    """Whether `ip` is actually serving the camera stream right now.

    The verdict of `probe_stream_result`, without the reason.
    """
    return (await probe_stream_result(ip, timeout)).ok


async def probe_stream_result(ip: str, timeout: float = PROBE_TIMEOUT) -> ProbeResult:
    """Whether `ip` is actually serving the camera stream right now, and why not.

    This exists because `config["active_patchers"]` is OUR bookkeeping, not the
    device's state. A factory reset or an app OTA wipes /system and takes the
    patch with it while our JSON still says applied; equally a device could be
    serving a stream nobody recorded. Advertising a URL that then refuses the
    connection is the failure `patchers/camera.py::stream_urls` already warns
    about — it reads as a broken camera rather than an unapplied patch.

    An open port is not enough, so this requires the FLV signature. Never raises:
    every failure is "no stream", which is the safe answer.

    The timeout is caught before `OSError` on purpose: since Python 3.11
    `asyncio.TimeoutError` IS `TimeoutError`, which is itself an `OSError`, so
    the other order would file every slow device under unreachable.
    """
    if not ip:
        return ProbeResult(False, REASON_NO_IP)
    url = f"http://{ip}/{STREAM_PATHS['flv']}"
    try:
        async with aiohttp.ClientSession() as session:
            async with session.get(url, timeout=aiohttp.ClientTimeout(total=timeout)) as resp:
                if resp.status != 200:
                    return ProbeResult(False, f"http_{resp.status}")
                head = await resp.content.readexactly(len(FLV_SIGNATURE))
    except (asyncio.TimeoutError, TimeoutError):
        return ProbeResult(False, REASON_TIMEOUT)
    except asyncio.IncompleteReadError:
        # A 200 that ended inside four bytes: something answered, no stream.
        return ProbeResult(False, REASON_BAD_SIGNATURE)
    except OSError as e:
        # Refused, host down, no route — `ClientConnectorError` is one too.
        log.debug("go2rtc: probe of %s could not connect: %s", ip, e)
        return ProbeResult(False, REASON_UNREACHABLE)
    except Exception as e:
        log.debug("go2rtc: probe of %s failed: %s", ip, e)
        return ProbeResult(False, REASON_ERROR)
    if head != FLV_SIGNATURE:
        return ProbeResult(False, REASON_BAD_SIGNATURE)
    return ProbeResult(True, REASON_OK)


def render_config(streams: dict[str, str]) -> str:
    """The go2rtc YAML for `streams`, as `{name: source url}`.

    Hand-rendered rather than via PyYAML: it is a fixed six-line document with
    one generated section, the values are URLs we built ourselves, and adding a
    serialiser dependency for it would be the larger change.

    `log.output` is `stdout` and never a file path: go2rtc 1.9.14 accepts only
    `stdout` or `stderr` there, and given a path it starts without complaint and
    logs nothing at all — measured, and the reason go2rtc.log never existed.
    `_start` points the child's stdout at the file instead.
    """
    lines = [
        "# Generated by petkit-local. Edits are overwritten.",
        "api:",
        f"  listen: {API_ADDR!r}",
        "rtsp:",
        f"  listen: ':{RTSP_PORT}'",
        "webrtc:",
        "  listen: ''",
        "log:",
        "  output: stdout",
        "streams:",
    ]
    for name, source in sorted(streams.items()):
        lines.append(f"  {name}: {source}")
    if not streams:
        lines.append("  {}")
    return "\n".join(lines) + "\n"


def _write_config(path: str, config: str) -> None:
    """Write the rendered config, creating its directory (blocking — call via
    a thread)."""
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(config)


def _open_log(path: str) -> Any:
    """Open go2rtc's log for the child to write, truncated (blocking — call via
    a thread).

    One run per file: the previous run's log is either already read or no
    longer interesting, and a file nobody rotates must not grow for the life of
    the install. 0600 because it carries the device addresses go2rtc dials, and
    the mode is re-applied because `O_CREAT`'s only covers a file it creates.
    """
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(fd, 0o600)
    return os.fdopen(fd, "wb")


class Go2rtc:
    """Runs go2rtc for as long as there is a camera worth serving.

    Same supervise/reconcile/stop shape as `mqtt/upstream.py::UpstreamMQTT`, for
    the same reason: what it should be doing is derived from shared state that
    something else mutates, so it is reconciled on a timer rather than driven by
    events.
    """

    def __init__(self, registry: DeviceRegistry, *, data_dir: str,
                 on_change: Any | None = None) -> None:
        """
        Args:
            on_change: Awaited after the child starts or stops, so whatever
                publishes `streamUrl` can re-publish it. Without this the sensor
                in Home Assistant stays empty until the device happens to report
                something — measured: the sidecar came up 21 seconds after the
                last state publish, and nothing republished for minutes.
        """
        self._registry = registry
        self._on_change = on_change
        self._config_path = os.path.join(data_dir, "go2rtc.yaml")
        self._log_path = os.path.join(data_dir, "go2rtc.log")
        self._proc: asyncio.subprocess.Process | None = None
        self._rendered = ""
        #: The streams the running child serves, so a change can be applied to
        #: just the ones that differ. Empty whenever `_rendered` is.
        self._streams: dict[str, str] = {}
        #: petkit_id -> (monotonic deadline, verdict). Probing costs one of the
        #: device's connections, and tserver only reliably has one.
        self._probes: dict[int, tuple[float, bool]] = {}
        #: petkit_id -> the last probe as the panel shows it (`probe_info`).
        #: Kept here and NOT in `device.state`, which is persisted: it describes
        #: this process's view of the network, and is no fact after a restart.
        self._probe_info: dict[int, dict[str, Any]] = {}

    @property
    def running(self) -> bool:
        """Whether our go2rtc child is alive right now."""
        return self._proc is not None and self._proc.returncode is None

    def stream_url_for(self, device: Device) -> str:
        """The RTSP URL for `device`, or "" when we are not serving it."""
        if not self.running or not device.state.get(STREAM_AVAILABLE):
            return ""
        host = advertised_host()
        return rtsp_url(device, host) if host else ""

    def probe_info(self, device: Device) -> dict[str, Any] | None:
        """The last probe of `device`, or None if it has not been probed yet.

        `ok` and `reason` are the verdict (`ProbeResult`); `checked_at` and
        `next_at` are wall-clock epoch seconds, `next_at` None when the next
        check waits on something other than time (a device with no IP). `busy`
        means the latest pass skipped the probe because somebody is watching:
        the verdict is then the one from before, and `checked_at` says when.
        """
        info = self._probe_info.get(device.petkit_id)
        return dict(info) if info is not None else None

    def _record_probe(self, device: Device, result: ProbeResult,
                      next_at: float | None) -> None:
        """Keep `result` for the panel, and log it only if it changed.

        A camera that stays down is re-probed every `PROBE_RETRY_SECONDS`, so
        logging each probe would put one identical line a minute in the log
        for as long as it stays down. A change is what somebody needs to see:
        losing a stream is a WARNING with its reason, getting one back an INFO.
        """
        prev = self._probe_info.get(device.petkit_id)
        self._probe_info[device.petkit_id] = {
            "ok": result.ok, "reason": result.reason,
            "checked_at": time.time(), "next_at": next_at, "busy": False,
        }
        if prev is not None and (prev["ok"], prev["reason"]) == tuple(result):
            return
        name = stream_name(device)
        if result.ok:
            log.info("go2rtc: camera %s is serving its stream", name)
        elif prev is None or prev["ok"]:
            log.warning("go2rtc: camera %s has no stream (%s)", name, result.reason)
        else:
            log.info("go2rtc: camera %s still has no stream, now %s (was %s)",
                     name, result.reason, prev["reason"])

    def desired_streams(self) -> dict[str, str]:
        """`{stream name: device FLV url}` for every confirmed camera."""
        streams = {}
        for device in self._registry.all():
            ip = (device.state or {}).get("ip", "")
            if ip and device.state.get(STREAM_AVAILABLE):
                streams[stream_name(device)] = f"http://{ip}/{STREAM_PATHS['flv']}"
        return streams

    def wanted(self) -> bool:
        """Whether go2rtc should be running: it exists and has a camera to serve."""
        return have_go2rtc() and bool(self.desired_streams())

    async def _watched_streams(self) -> set[str]:
        """Stream names go2rtc currently has a viewer on.

        This is the set whose device connection must not be disturbed. It is
        asked of go2rtc rather than inferred from `self.running`, and that
        distinction is load-bearing: go2rtc stays up for as long as any camera
        is configured, but it only dials a device while somebody is watching.
        Treating "process alive" as "connection held" would suspend probing for
        good, and a device that quietly stopped serving would keep its URL
        advertised forever — the exact staleness the probe exists to prevent.
        """
        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(
                    f"http://{API_ADDR}/api/streams",
                    timeout=aiohttp.ClientTimeout(total=2),
                ) as resp:
                    streams = await resp.json()
        except Exception:
            # Cannot tell — assume every stream is in use. Skipping a probe only
            # delays a verdict; stealing a live viewer's connection breaks it.
            return set(self.desired_streams())
        return {name for name, info in (streams or {}).items() if (info or {}).get("consumers")}

    async def refresh_probes(self) -> None:
        """Re-probe cameras whose verdict has expired.

        A device being watched right now is skipped: go2rtc holds its only
        connection, and a probe would take the slot and come back saying there
        is no stream.
        """
        busy = await self._watched_streams() if self.running else set()
        now = time.monotonic()
        for device in self._registry.all():
            if not device.is_camera:
                continue
            if stream_name(device) in busy:
                info = self._probe_info.setdefault(device.petkit_id, {
                    "ok": bool(device.state.get(STREAM_AVAILABLE)), "reason": None,
                    "checked_at": None, "next_at": None, "busy": True,
                })
                info["busy"] = True
                continue
            ip = (device.state or {}).get("ip", "")
            if not ip:
                device.state.pop(STREAM_AVAILABLE, None)
                self._probes.pop(device.petkit_id, None)
                self._record_probe(device, ProbeResult(False, REASON_NO_IP), None)
                continue
            deadline, _ = self._probes.get(device.petkit_id, (0.0, False))
            if deadline > now:
                if device.petkit_id in self._probe_info:
                    self._probe_info[device.petkit_id]["busy"] = False
                continue
            result = await probe_stream_result(ip)
            available = result.ok
            ttl = PROBE_TTL_SECONDS if available else PROBE_RETRY_SECONDS
            self._probes[device.petkit_id] = (now + ttl, available)
            self._record_probe(device, result, time.time() + ttl)
            if available:
                device.state[STREAM_AVAILABLE] = True
            else:
                device.state.pop(STREAM_AVAILABLE, None)

    async def supervise(self) -> None:
        """Reconcile forever, and take the child down with us.

        The cancel handler wraps the WHOLE loop, sleep included. That is not
        tidiness: this task spends essentially all of its life in the sleep, so
        a handler around only the reconcile body would miss the cancellation
        that actually happens and leave go2rtc running after shutdown — holding
        both the RTSP port and the device's one connection.
        """
        try:
            while True:
                try:
                    await self.refresh_probes()
                    await self.reconcile()
                except asyncio.CancelledError:
                    raise
                except Exception:
                    log.exception("go2rtc supervisor pass failed")
                await asyncio.sleep(SUPERVISE_INTERVAL_SECONDS)
        except asyncio.CancelledError:
            await self.stop()
            raise

    async def reconcile(self) -> None:
        """Start, update or stop the child to match what the devices need.

        A change to the stream set of a RUNNING child goes through go2rtc's API,
        one stream at a time, and only falls back to a restart if that fails. A
        restart drops every viewer of every camera — in production one camera's
        stream appearing cut off the other camera's live view — when all that
        changed was a camera nobody was watching.
        """
        if not self.wanted():
            if self.running:
                log.info("go2rtc: no camera to serve, stopping")
            await self.stop()
            return

        streams = self.desired_streams()
        config = render_config(streams)
        if self.running and config == self._rendered:
            return

        if self.running:
            if await self._update_streams(self._streams, streams):
                # Written back so a later restart serves exactly this. go2rtc
                # has already rewritten the file itself on each PUT/DELETE, in
                # its own layout; ours replaces it so `_rendered` matches.
                await asyncio.to_thread(_write_config, self._config_path, config)
                self._rendered = config
                self._streams = streams
                log.info("go2rtc: now serving %d stream(s), updated in place", len(streams))
                await self._notify()
                return
            log.warning("go2rtc: could not update the stream set in place, restarting")
            await self.stop()

        await asyncio.to_thread(_write_config, self._config_path, config)
        self._rendered = config
        self._streams = streams
        await self._start()

    async def _update_streams(self, current: dict[str, str],
                              desired: dict[str, str]) -> bool:
        """Turn the running child's `current` streams into `desired` via its API.

        go2rtc 1.9.14, measured on an isolated instance: `PUT /api/streams?name=
        &src=` adds a stream or replaces its source, `DELETE /api/streams?src=
        <name>` removes one, and a viewer of an untouched stream keeps playing
        throughout. Both answer 200 for things they did not do — DELETE for a
        name it does not have, or given `name=` instead of `src=` — so success
        is judged by what `/api/streams` lists afterwards, not by status codes.

        Never raises; False means "restart instead".
        """
        url = f"http://{API_ADDR}/api/streams"
        try:
            async with aiohttp.ClientSession(
                    timeout=aiohttp.ClientTimeout(total=5)) as session:
                for name in sorted(set(current) - set(desired)):
                    async with session.delete(url, params={"src": name}) as resp:
                        if resp.status != 200:
                            return False
                for name, source in sorted(desired.items()):
                    if current.get(name) == source:
                        continue
                    async with session.put(url, params={"name": name, "src": source}) as resp:
                        if resp.status != 200:
                            return False
                async with session.get(url) as resp:
                    listed = await resp.json() if resp.status == 200 else None
        except Exception as e:
            log.debug("go2rtc: updating streams through the API failed: %s", e)
            return False
        if not isinstance(listed, dict) or set(listed) != set(desired):
            return False
        for name, source in desired.items():
            producers = (listed.get(name) or {}).get("producers") or []
            if source not in [(p or {}).get("url") for p in producers]:
                return False
        return True

    async def _start(self) -> None:
        """Spawn go2rtc against the config already written.

        Never raises: a camera that cannot be served must not take the add-on
        down with it, so a failure is logged and leaves `running` False.
        """
        # A file rather than pipes: this child outlives the call, and one whose
        # output is never drained eventually blocks on a full pipe. stderr joins
        # stdout, which is where go2rtc logs (see render_config) — and where a
        # Go panic lands, which is the line most worth having.
        try:
            log_file = await asyncio.to_thread(_open_log, self._log_path)
        except OSError as e:
            log.warning("go2rtc: cannot open %s, its output is discarded: %s",
                        self._log_path, e)
            log_file = None
        try:
            self._proc = await asyncio.create_subprocess_exec(
                "go2rtc", "-c", self._config_path,
                stdout=log_file if log_file is not None else asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.STDOUT,
            )
        except OSError as e:
            # Same contract as `transcode.run_ffmpeg`: a missing or unrunnable
            # binary degrades, it does not take the add-on down.
            log.warning("go2rtc could not be started: %s", e)
            self._proc = None
            return
        finally:
            # The child holds its own descriptor now; ours would leak one per
            # restart, and keep the file open after the child exits.
            if log_file is not None:
                log_file.close()
        log.info("go2rtc serving %d stream(s) on RTSP :%d (pid %d)",
                 len(self.desired_streams()), RTSP_PORT, self._proc.pid)
        await self._notify()

    async def stop(self) -> None:
        """Kill and reap the child. Idempotent, and safe to call twice."""
        proc, self._proc = self._proc, None
        self._rendered = ""
        self._streams = {}
        if proc is None or proc.returncode is not None:
            return
        with contextlib.suppress(ProcessLookupError):
            proc.kill()
        # Reap, so it cannot become a zombie for the life of the container.
        with contextlib.suppress(asyncio.TimeoutError, Exception):
            await asyncio.wait_for(proc.wait(), 10)
        await self._notify()

    async def _notify(self) -> None:
        """Tell the caller the URLs just changed. Never fatal: this is a courtesy
        re-publish, and failing it must not take the supervisor down."""
        if self._on_change is None:
            return
        try:
            await self._on_change()
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("go2rtc: notifying of a stream change failed")


def stream_urls_with_rtsp(device: Device, supervisor: Any | None) -> dict[str, str]:
    """The device's own URLs plus the RTSP one, when there is a supervisor.

    Kept out of `patchers/camera.py` so that module stays about the patch and
    knows nothing about go2rtc.
    """
    urls = dict(stream_urls(device))
    rtsp = supervisor.stream_url_for(device) if supervisor is not None else ""
    if rtsp:
        return {"rtsp": rtsp, **urls}
    return urls
