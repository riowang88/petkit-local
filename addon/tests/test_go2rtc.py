"""The camera sidecar: config generation, the probe, and the child process."""
import asyncio
import logging
import os
import stat
from types import SimpleNamespace

import pytest
from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer

from petkit_local.devices.base import Device
from petkit_local.devices.registry import DeviceRegistry
from petkit_local.media import go2rtc as g


@pytest.fixture(autouse=True)
def _binary_present(monkeypatch):
    """`have_go2rtc` memoises, so pin it rather than letting one test's answer
    leak into the next."""
    monkeypatch.setattr(g, "_have_go2rtc_cache", True)
    yield
    g._have_go2rtc_cache = None


@pytest.fixture(autouse=True)
def _no_real_go2rtc_api(monkeypatch):
    """Point every API call at port 1, reserved and never listening.

    The default `API_ADDR` is where a REAL go2rtc listens, and on a machine that
    runs one, a test reaching for it would read that instance's streams — or,
    with the in-place stream updates, PUT and DELETE them. A test that wants an
    API stands up its own server and points `API_ADDR` at it.
    """
    monkeypatch.setattr(g, "API_ADDR", "127.0.0.1:1")


def _registry(*devices):
    reg = DeviceRegistry()
    for d in devices:
        reg._devices[d.petkit_id] = d
    return reg


def _cam(petkit_id=1, ip="192.0.2.10", available=True):
    d = Device(device_type="t5", petkit_id=petkit_id, serial_number=f"SN{petkit_id}")
    if ip:
        d.state["ip"] = ip
    if available:
        d.state[g.STREAM_AVAILABLE] = True
    return d


def _sidecar(reg, tmp_path):
    return g.Go2rtc(reg, data_dir=str(tmp_path))


# --- config generation ------------------------------------------------------

def test_no_streams_when_nothing_is_confirmed(tmp_path):
    assert _sidecar(_registry(), tmp_path).desired_streams() == {}
    assert _sidecar(_registry(_cam(available=False)), tmp_path).desired_streams() == {}
    assert _sidecar(_registry(_cam(ip="")), tmp_path).desired_streams() == {}


def test_a_stream_per_confirmed_camera(tmp_path):
    s = _sidecar(_registry(_cam(1), _cam(2, ip="192.0.2.11")), tmp_path)
    assert s.desired_streams() == {
        "1": "http://192.0.2.10/main.flv?audio=1",
        "2": "http://192.0.2.11/main.flv?audio=1",
    }


def test_the_stream_name_is_the_petkit_id():
    """The RTSP path ends up in the user's camera config, so it has to be the
    one identifier that is stable and not the owner's to rename."""
    assert g.stream_name(_cam(30020324)) == "30020324"


def test_the_rendered_config_binds_where_we_intend():
    out = g.render_config({"1": "http://d/main.flv?audio=1"})
    assert f"listen: ':{g.RTSP_PORT}'" in out
    # The API is loopback-only and WebRTC is off outright: an RTSP source needs
    # neither, and both would be surface for nothing.
    # (The port is `_no_real_go2rtc_api`'s; the host is what this checks.)
    assert "api:\n  listen: '127.0.0.1:" in out
    assert "webrtc:\n  listen: ''" in out
    assert "  1: http://d/main.flv?audio=1" in out


def test_an_empty_stream_set_still_renders_valid_yaml():
    assert "streams:\n  {}" in g.render_config({})


def test_wanted_needs_both_the_binary_and_a_camera(tmp_path, monkeypatch):
    assert _sidecar(_registry(_cam()), tmp_path).wanted() is True
    assert _sidecar(_registry(), tmp_path).wanted() is False
    monkeypatch.setattr(g, "_have_go2rtc_cache", False)
    assert _sidecar(_registry(_cam()), tmp_path).wanted() is False


# --- the probe --------------------------------------------------------------

async def _serving(body, status=200):
    async def handler(request):
        return web.Response(body=body, status=status)

    app = web.Application()
    app.router.add_route("*", "/{path:.*}", handler)
    client = TestClient(TestServer(app))
    await client.start_server()
    return client, f"127.0.0.1:{client.server.port}"


async def test_the_probe_accepts_an_flv_stream():
    client, addr = await _serving(g.FLV_SIGNATURE + b"\x00" * 64)
    try:
        assert await g.probe_stream(addr) is True
    finally:
        await client.close()


async def test_temporarily_unavailable_camera_can_recover_within_a_minute(tmp_path, monkeypatch):
    clock = [100.0]
    ready = [False]
    calls = []

    async def media(request):
        calls.append(request.path)
        return web.Response(body=g.FLV_SIGNATURE if ready[0] else b"not ready", status=200)

    app = web.Application()
    app.router.add_get("/main.flv", media)
    server = TestServer(app)
    await server.start_server()
    monkeypatch.setattr(g, "time", SimpleNamespace(monotonic=lambda: clock[0],
                                                   time=lambda: clock[0]))
    camera = _cam(ip=f"127.0.0.1:{server.port}", available=False)
    sidecar = _sidecar(_registry(camera), tmp_path)
    try:
        await sidecar.refresh_probes()
        ready[0] = True
        clock[0] = 159.9
        await sidecar.refresh_probes()
        assert len(calls) == 1
        clock[0] = 160.1
        await sidecar.refresh_probes()
        assert len(calls) == 2
        assert camera.state.get(g.STREAM_AVAILABLE) is True
    finally:
        await server.close()


async def test_an_open_port_is_not_a_stream():
    """The reason the probe reads bytes at all: something else could be
    listening on 80, and a 200 proves nothing about what it serves."""
    client, addr = await _serving(b"<html>hello</html>")
    try:
        assert await g.probe_stream(addr) is False
    finally:
        await client.close()


async def test_a_non_200_is_not_a_stream():
    client, addr = await _serving(g.FLV_SIGNATURE, status=404)
    try:
        assert await g.probe_stream(addr) is False
    finally:
        await client.close()


async def test_a_refused_connection_is_an_answer_not_an_exception():
    """Port 1 is reserved and never listening. Every probe failure has to be
    'no stream' — this runs on a timer and must not take the supervisor down."""
    assert await g.probe_stream("127.0.0.1:1") is False


async def test_no_ip_is_no_stream():
    assert await g.probe_stream("") is False


async def test_the_probe_answer_is_cached(tmp_path, monkeypatch):
    """Probing costs one of the device's connections and tserver only reliably
    has one, so the same question must not be asked every pass."""
    calls = []

    async def counting(ip, *a, **kw):
        calls.append(ip)
        return g.ProbeResult(True, g.REASON_OK)

    monkeypatch.setattr(g, "probe_stream_result", counting)
    s = _sidecar(_registry(_cam(available=False)), tmp_path)
    await s.refresh_probes()
    await s.refresh_probes()
    assert len(calls) == 1


class _Alive:
    returncode = None


async def test_probing_is_skipped_for_a_stream_someone_is_watching(tmp_path, monkeypatch):
    """go2rtc holds that device's only connection while a viewer is attached, so
    probing would take the slot and come back saying there is no stream."""
    calls = []

    async def counting(ip, *a, **kw):
        calls.append(ip)
        return g.ProbeResult(True, g.REASON_OK)

    async def watched(self):
        return {"1"}

    monkeypatch.setattr(g, "probe_stream_result", counting)
    monkeypatch.setattr(g.Go2rtc, "_watched_streams", watched)
    s = _sidecar(_registry(_cam()), tmp_path)
    s._proc = _Alive()

    await s.refresh_probes()
    assert calls == []


async def test_probing_continues_while_go2rtc_runs_with_nobody_watching(tmp_path, monkeypatch):
    """The gap this closes: go2rtc stays up for as long as any camera is
    configured, but only dials the device while someone watches. Treating
    'process alive' as 'connection held' would suspend probing forever, and a
    device that quietly stopped serving would keep its URL advertised."""
    calls = []

    async def counting(ip, *a, **kw):
        calls.append(ip)
        return g.ProbeResult(False, g.REASON_UNREACHABLE)

    async def nobody(self):
        return set()

    monkeypatch.setattr(g, "probe_stream_result", counting)
    monkeypatch.setattr(g.Go2rtc, "_watched_streams", nobody)
    d = _cam()
    s = _sidecar(_registry(d), tmp_path)
    s._proc = _Alive()

    await s.refresh_probes()
    assert calls == ["192.0.2.10"]
    assert g.STREAM_AVAILABLE not in d.state


async def test_an_unreachable_go2rtc_api_suspends_probing_rather_than_guessing(tmp_path, monkeypatch):
    """Skipping a probe only delays a verdict; stealing a live viewer's
    connection breaks it. So 'cannot tell' errs towards not probing.

    Unreachable because `_no_real_go2rtc_api` points the API at port 1 — not
    because nothing happens to be listening on the real port."""
    s = _sidecar(_registry(_cam()), tmp_path)
    s._proc = _Alive()
    assert await s._watched_streams() == {"1"}


async def test_a_failed_probe_clears_a_previous_yes(tmp_path, monkeypatch):
    async def gone(ip, *a, **kw):
        return g.ProbeResult(False, g.REASON_UNREACHABLE)

    monkeypatch.setattr(g, "probe_stream_result", gone)
    d = _cam()
    s = _sidecar(_registry(d), tmp_path)
    await s.refresh_probes()
    assert g.STREAM_AVAILABLE not in d.state


# --- the child process ------------------------------------------------------

async def test_stop_is_idempotent_and_safe_before_any_start(tmp_path):
    s = _sidecar(_registry(), tmp_path)
    await s.stop()
    await s.stop()
    assert s.running is False


async def test_a_missing_binary_degrades_instead_of_raising(tmp_path, monkeypatch):
    """Same contract as ffmpeg: the add-on keeps running without it."""
    s = _sidecar(_registry(_cam()), tmp_path)
    monkeypatch.setattr(g.asyncio, "create_subprocess_exec",
                        lambda *a, **kw: (_ for _ in ()).throw(OSError("nope")))
    await s.reconcile()
    assert s.running is False


async def test_the_child_is_killed_when_the_supervisor_is_cancelled(tmp_path, monkeypatch):
    """An orphaned go2rtc would hold both the RTSP port and the device's one
    connection past our exit."""
    started = asyncio.Event()
    spawned = []
    # Bind the real one first: the patch below replaces the module attribute,
    # so a fake that reached for it by name would call itself.
    real_exec = asyncio.create_subprocess_exec

    async def fake_exec(*args, **kwargs):
        proc = await real_exec("sleep", "300", stdout=asyncio.subprocess.DEVNULL,
                               stderr=asyncio.subprocess.DEVNULL)
        # Capture here and signal AFTER: `_start` only assigns `self._proc` once
        # this returns, so signalling first would race the assignment.
        spawned.append(proc)
        started.set()
        return proc

    async def _confirmed(ip, *a, **kw):
        return g.ProbeResult(True, g.REASON_OK)

    monkeypatch.setattr(g, "probe_stream_result", _confirmed)
    monkeypatch.setattr(g.asyncio, "create_subprocess_exec", fake_exec)
    s = _sidecar(_registry(_cam()), tmp_path)
    task = asyncio.create_task(s.supervise())
    await asyncio.wait_for(started.wait(), 5)

    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task
    assert spawned and spawned[0].returncode is not None, "go2rtc was left running"


async def test_the_config_is_only_rewritten_when_the_stream_set_changes(tmp_path, monkeypatch):
    """A restart drops every viewer, so it must not happen on a timer."""
    execs = []
    real_exec = asyncio.create_subprocess_exec

    async def fake_exec(*args, **kwargs):
        execs.append(args)
        return await real_exec("sleep", "300", stdout=asyncio.subprocess.DEVNULL,
                               stderr=asyncio.subprocess.DEVNULL)

    monkeypatch.setattr(g.asyncio, "create_subprocess_exec", fake_exec)
    s = _sidecar(_registry(_cam()), tmp_path)
    try:
        await s.reconcile()
        await s.reconcile()
        await s.reconcile()
        assert len(execs) == 1
    finally:
        await s.stop()


# --- why a probe failed -----------------------------------------------------
#
# `streamAvailable` going missing said only "no". These pin down the reason it
# now carries, because "the Mac cannot reach the device" and "the device is up
# but the patch is gone" need different fixes and looked identical.

async def test_a_served_stream_is_ok():
    client, addr = await _serving(g.FLV_SIGNATURE + b"\x00" * 64)
    try:
        assert await g.probe_stream_result(addr) == g.ProbeResult(True, g.REASON_OK)
    finally:
        await client.close()


async def test_a_non_200_names_its_status():
    client, addr = await _serving(g.FLV_SIGNATURE, status=404)
    try:
        assert await g.probe_stream_result(addr) == g.ProbeResult(False, "http_404")
    finally:
        await client.close()


@pytest.mark.parametrize("body", [b"<html>hello</html>", b""])
async def test_a_200_without_the_flv_signature_is_a_bad_signature(body):
    """Short or empty counts too: the port answered, the stream is not there."""
    client, addr = await _serving(body)
    try:
        assert await g.probe_stream_result(addr) == g.ProbeResult(False, g.REASON_BAD_SIGNATURE)
    finally:
        await client.close()


async def test_a_refused_connection_is_unreachable():
    assert await g.probe_stream_result("127.0.0.1:1") == g.ProbeResult(False, g.REASON_UNREACHABLE)


async def test_a_device_that_never_answers_is_a_timeout():
    async def stall(request):
        await asyncio.sleep(5)
        return web.Response(body=g.FLV_SIGNATURE)

    app = web.Application()
    app.router.add_get("/main.flv", stall)
    client = TestClient(TestServer(app, shutdown_timeout=0.1))
    await client.start_server()
    try:
        result = await g.probe_stream_result(f"127.0.0.1:{client.server.port}", timeout=0.2)
        assert result == g.ProbeResult(False, g.REASON_TIMEOUT)
    finally:
        await client.close()


async def test_no_ip_is_its_own_reason():
    assert await g.probe_stream_result("") == g.ProbeResult(False, g.REASON_NO_IP)


async def test_anything_else_is_an_error_not_an_exception():
    assert await g.probe_stream_result("[not-a-host") == g.ProbeResult(False, g.REASON_ERROR)


def _scripted(monkeypatch, results):
    """Make each probe return the next of `results`, and the clock jump past any
    TTL between passes, so every `refresh_probes` really probes."""
    queue = list(results)
    clock = [1000.0]

    async def scripted(ip, *a, **kw):
        return queue.pop(0)

    def tick():
        clock[0] += g.PROBE_TTL_SECONDS + 1
        return clock[0]

    monkeypatch.setattr(g, "probe_stream_result", scripted)
    monkeypatch.setattr(g, "time", SimpleNamespace(monotonic=lambda: clock[0],
                                                   time=lambda: clock[0] + 5e8))
    return tick


async def test_only_a_changed_verdict_is_logged(tmp_path, monkeypatch, caplog):
    """A camera that stays unreachable is probed every minute, and logging every
    one of those would bury everything else in the log."""
    down = g.ProbeResult(False, g.REASON_UNREACHABLE)
    up = g.ProbeResult(True, g.REASON_OK)
    tick = _scripted(monkeypatch, [down, down, up, up, g.ProbeResult(False, g.REASON_TIMEOUT)])
    s = _sidecar(_registry(_cam(available=False)), tmp_path)
    caplog.set_level(logging.DEBUG, logger=g.log.name)
    for _ in range(5):
        await s.refresh_probes()
        tick()

    records = [(r.levelno, r.getMessage()) for r in caplog.records
               if r.name == g.log.name and r.levelno >= logging.INFO]
    assert [level for level, _ in records] == [logging.WARNING, logging.INFO, logging.WARNING]
    assert "unreachable" in records[0][1]
    assert "timeout" in records[2][1]


async def test_the_last_probe_is_kept_in_memory_and_not_in_device_state(tmp_path, monkeypatch):
    _scripted(monkeypatch, [g.ProbeResult(False, g.REASON_TIMEOUT)])
    d = _cam(available=False)
    s = _sidecar(_registry(d), tmp_path)
    assert s.probe_info(d) is None
    await s.refresh_probes()

    info = s.probe_info(d)
    assert info["ok"] is False
    assert info["reason"] == g.REASON_TIMEOUT
    assert info["busy"] is False
    # Wall-clock epoch, so the panel can render it; a failure retries sooner.
    assert info["checked_at"] == 1000.0 + 5e8
    assert info["next_at"] - info["checked_at"] == g.PROBE_RETRY_SECONDS
    # Derived and transient: nothing of it may reach the persisted device.
    assert set(d.state) == {"ip"}


async def test_a_device_with_no_ip_says_so(tmp_path):
    d = _cam(ip="", available=False)
    s = _sidecar(_registry(d), tmp_path)
    await s.refresh_probes()
    assert s.probe_info(d)["reason"] == g.REASON_NO_IP
    assert s.probe_info(d)["ok"] is False


async def test_a_watched_device_is_marked_busy_and_keeps_its_last_verdict(tmp_path, monkeypatch):
    tick = _scripted(monkeypatch, [g.ProbeResult(True, g.REASON_OK)])
    d = _cam()
    s = _sidecar(_registry(d), tmp_path)
    await s.refresh_probes()
    checked = s.probe_info(d)["checked_at"]
    tick()

    async def watched(self):
        return {"1"}

    monkeypatch.setattr(g.Go2rtc, "_watched_streams", watched)
    s._proc = _Alive()
    await s.refresh_probes()

    info = s.probe_info(d)
    assert info["busy"] is True
    assert (info["ok"], info["reason"], info["checked_at"]) == (True, g.REASON_OK, checked)


async def test_probe_info_is_a_copy(tmp_path, monkeypatch):
    _scripted(monkeypatch, [g.ProbeResult(True, g.REASON_OK)])
    d = _cam()
    s = _sidecar(_registry(d), tmp_path)
    await s.refresh_probes()
    s.probe_info(d)["ok"] = "tampered"
    assert s.probe_info(d)["ok"] is True


# --- go2rtc's own log -------------------------------------------------------

def test_the_log_goes_to_stdout_which_is_all_go2rtc_accepts():
    """go2rtc 1.9.14 reads `log.output` as `stdout`/`stderr` only. A file path
    there is accepted without complaint and logs NOTHING — which is why
    go2rtc.log never existed. Its stdout is redirected to the file instead."""
    out = g.render_config({"1": "http://d/main.flv?audio=1"})
    assert "log:\n  output: stdout\n" in out
    assert ".log" not in out


async def test_the_child_writes_to_a_fresh_private_log_file(tmp_path, monkeypatch):
    log_path = tmp_path / "go2rtc.log"
    log_path.write_text("left over from the last run\n")
    os.chmod(log_path, 0o644)
    passed = []
    real_exec = asyncio.create_subprocess_exec

    async def fake_exec(*args, **kwargs):
        passed.append(kwargs)
        proc = await real_exec("sh", "-c", "echo to-stdout; echo to-stderr >&2",
                               stdout=kwargs["stdout"], stderr=kwargs["stderr"])
        await proc.wait()
        return proc

    monkeypatch.setattr(g.asyncio, "create_subprocess_exec", fake_exec)
    s = _sidecar(_registry(_cam()), tmp_path)
    await s.reconcile()

    assert passed[0]["stderr"] == asyncio.subprocess.STDOUT
    # The child has its own copy; ours would otherwise leak one per restart.
    assert passed[0]["stdout"].closed
    text = log_path.read_text()
    assert "to-stdout" in text and "to-stderr" in text
    assert "left over" not in text
    assert stat.S_IMODE(os.stat(log_path).st_mode) == 0o600


async def test_an_unopenable_log_still_starts_go2rtc(tmp_path, monkeypatch):
    """The log is for diagnosis; losing it must not lose the camera."""
    passed = []
    real_exec = asyncio.create_subprocess_exec

    async def fake_exec(*args, **kwargs):
        passed.append(kwargs)
        return await real_exec("sleep", "300", stdout=asyncio.subprocess.DEVNULL,
                               stderr=asyncio.subprocess.DEVNULL)

    def refuse(path):
        raise PermissionError("read-only")

    monkeypatch.setattr(g.asyncio, "create_subprocess_exec", fake_exec)
    monkeypatch.setattr(g, "_open_log", refuse)
    s = _sidecar(_registry(_cam()), tmp_path)
    try:
        await s.reconcile()
        assert s.running
        assert passed[0]["stdout"] == asyncio.subprocess.DEVNULL
    finally:
        await s.stop()


# --- changing streams without a restart -------------------------------------

class _FakeGo2rtcApi:
    """go2rtc's `/api/streams`, as 1.9.14 answers it (measured on an isolated
    instance): PUT `name`+`src` adds or replaces, DELETE `src`=<name> removes,
    and both answer 200 — DELETE even for a name it does not have."""

    def __init__(self, streams=None, fail=None, ignore_delete=False):
        self.streams = dict(streams or {})
        self.calls = []
        self.fail = fail
        self.ignore_delete = ignore_delete

    async def handle(self, request):
        q = request.query
        if request.method != "GET":
            self.calls.append((request.method, dict(q)))
        if request.method == self.fail:
            return web.Response(status=500, text="boom")
        if request.method == "PUT":
            self.streams[q["name"]] = q["src"]
            return web.Response()
        if request.method == "DELETE":
            if not self.ignore_delete:
                self.streams.pop(q["src"], None)
            return web.Response()
        return web.json_response({
            name: {"producers": [{"url": src}], "consumers": None}
            for name, src in self.streams.items()})

    async def start(self, monkeypatch):
        app = web.Application()
        app.router.add_route("*", "/api/streams", self.handle)
        self.server = TestServer(app, shutdown_timeout=0.1)
        await self.server.start_server()
        monkeypatch.setattr(g, "API_ADDR", f"127.0.0.1:{self.server.port}")
        return self


async def _running(tmp_path, monkeypatch, reg):
    """A sidecar whose child is a harmless `sleep`, started for `reg`."""
    execs = []
    real_exec = asyncio.create_subprocess_exec

    async def fake_exec(*args, **kwargs):
        execs.append(args)
        return await real_exec("sleep", "300", stdout=asyncio.subprocess.DEVNULL,
                               stderr=asyncio.subprocess.DEVNULL)

    monkeypatch.setattr(g.asyncio, "create_subprocess_exec", fake_exec)
    s = _sidecar(reg, tmp_path)
    await s.reconcile()
    assert len(execs) == 1
    return s, execs


def _no_restart(s, monkeypatch):
    calls = []

    async def spy_stop():
        calls.append("stop")

    async def spy_start():
        calls.append("start")

    monkeypatch.setattr(s, "stop", spy_stop)
    monkeypatch.setattr(s, "_start", spy_start)
    return calls


async def test_a_new_camera_is_added_without_restarting(tmp_path, monkeypatch):
    """A restart drops the viewer of every OTHER camera — measured in
    production as `stream set changed, restarting` cutting a live view."""
    reg = _registry(_cam(1))
    api = await _FakeGo2rtcApi({"1": "http://192.0.2.10/main.flv?audio=1"}).start(monkeypatch)
    s, execs = await _running(tmp_path, monkeypatch, reg)
    notified = []

    async def on_change():
        notified.append(True)

    s._on_change = on_change
    try:
        reg._devices[2] = _cam(2, ip="192.0.2.11")
        restarts = _no_restart(s, monkeypatch)
        await s.reconcile()

        assert restarts == []
        assert api.calls == [("PUT", {"name": "2", "src": "http://192.0.2.11/main.flv?audio=1"})]
        expected = g.render_config(s.desired_streams())
        assert (tmp_path / "go2rtc.yaml").read_text() == expected
        assert s._rendered == expected
        assert notified, "streamUrl would not be republished for the new camera"

        # And the next pass sees nothing to do.
        await s.reconcile()
        assert len(api.calls) == 1
    finally:
        # The class's own stop: the instance one is the spy. (Not
        # `monkeypatch.undo()`, which would put `API_ADDR` back on the real port.)
        await g.Go2rtc.stop(s)
        await api.server.close()


async def test_a_lost_camera_is_removed_without_restarting(tmp_path, monkeypatch):
    two = _cam(2, ip="192.0.2.11")
    reg = _registry(_cam(1), two)
    api = await _FakeGo2rtcApi({
        "1": "http://192.0.2.10/main.flv?audio=1",
        "2": "http://192.0.2.11/main.flv?audio=1",
    }).start(monkeypatch)
    s, execs = await _running(tmp_path, monkeypatch, reg)
    try:
        two.state.pop(g.STREAM_AVAILABLE)
        restarts = _no_restart(s, monkeypatch)
        await s.reconcile()

        assert restarts == []
        assert api.calls == [("DELETE", {"src": "2"})]
        assert "  2:" not in (tmp_path / "go2rtc.yaml").read_text()
        assert s._rendered == g.render_config({"1": "http://192.0.2.10/main.flv?audio=1"})
    finally:
        # The class's own stop: the instance one is the spy. (Not
        # `monkeypatch.undo()`, which would put `API_ADDR` back on the real port.)
        await g.Go2rtc.stop(s)
        await api.server.close()


async def test_a_moved_camera_is_repointed_in_place(tmp_path, monkeypatch):
    one = _cam(1)
    reg = _registry(one)
    api = await _FakeGo2rtcApi({"1": "http://192.0.2.10/main.flv?audio=1"}).start(monkeypatch)
    s, execs = await _running(tmp_path, monkeypatch, reg)
    try:
        one.state["ip"] = "192.0.2.99"
        restarts = _no_restart(s, monkeypatch)
        await s.reconcile()
        assert restarts == []
        assert api.calls == [("PUT", {"name": "1", "src": "http://192.0.2.99/main.flv?audio=1"})]
    finally:
        # The class's own stop: the instance one is the spy. (Not
        # `monkeypatch.undo()`, which would put `API_ADDR` back on the real port.)
        await g.Go2rtc.stop(s)
        await api.server.close()


@pytest.mark.parametrize("api_kwargs", [
    {"fail": "PUT"},
    # go2rtc answers 200 to a DELETE it did not carry out, so the result is
    # checked against what it then lists, not against the status code.
    {"ignore_delete": True},
])
async def test_an_update_go2rtc_did_not_take_falls_back_to_a_restart(tmp_path, monkeypatch, api_kwargs):
    two = _cam(2, ip="192.0.2.11")
    reg = _registry(_cam(1), two)
    api = await _FakeGo2rtcApi({
        "1": "http://192.0.2.10/main.flv?audio=1",
        "2": "http://192.0.2.11/main.flv?audio=1",
    }, **api_kwargs).start(monkeypatch)
    s, execs = await _running(tmp_path, monkeypatch, reg)
    try:
        if api_kwargs.get("fail") == "PUT":
            reg._devices[3] = _cam(3, ip="192.0.2.12")
        else:
            two.state.pop(g.STREAM_AVAILABLE)
        await s.reconcile()
        assert len(execs) == 2, "the update failed and nothing restarted"
        assert s.running
        assert s._rendered == g.render_config(s.desired_streams())
    finally:
        await s.stop()
        await api.server.close()


async def test_an_unreachable_api_falls_back_to_a_restart(tmp_path, monkeypatch):
    """`_no_real_go2rtc_api` leaves the API on port 1, so every call fails."""
    reg = _registry(_cam(1))
    s, execs = await _running(tmp_path, monkeypatch, reg)
    try:
        reg._devices[2] = _cam(2, ip="192.0.2.11")
        await s.reconcile()
        assert len(execs) == 2
        assert "  2: " in (tmp_path / "go2rtc.yaml").read_text()
    finally:
        await s.stop()


async def test_the_last_camera_leaving_still_stops_go2rtc(tmp_path, monkeypatch):
    one = _cam(1)
    reg = _registry(one)
    api = await _FakeGo2rtcApi({"1": "http://192.0.2.10/main.flv?audio=1"}).start(monkeypatch)
    s, execs = await _running(tmp_path, monkeypatch, reg)
    try:
        one.state.pop(g.STREAM_AVAILABLE)
        await s.reconcile()
        assert not s.running
        assert api.calls == []
    finally:
        await s.stop()
        await api.server.close()
