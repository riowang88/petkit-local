"""Tests for the read-only device probe (web/api/patchers.py::_run_probe)."""
import petkit_local.web.api.patchers as P
from petkit_local.devices.base import Device


class _Hub:
    def __init__(self):
        self.lines = []

    def publish(self, kind, device_id, summary):
        self.lines.append(summary)


def _fakes(monkeypatch, output):
    seen = {"commands": [], "sent": []}

    async def capture(device, ip, command, *, bridge=None, timeout=60.0):
        seen["commands"].append(command)
        return output

    async def send(device, command, bridge=None):
        seen["sent"].append(command)
        return "heartbeat"

    monkeypatch.setattr(P, "run_cmd_capture", capture)
    monkeypatch.setattr(P, "send_run_cmd", send)
    return seen


async def test_probe_runs_only_the_fixed_command_and_reports_each_line(monkeypatch):
    seen = _fakes(monkeypatch, "uid=0(root) gid=0(root)\n93b0  /app/bin/ble")
    hub = _Hub()
    await P._run_probe(Device(device_type="t6", petkit_id=7), "10.0.0.5", hub, {})

    assert seen["commands"] == [P.PROBE_COMMAND]
    assert "[probe] uid=0(root) gid=0(root)" in hub.lines
    assert "[probe] 93b0  /app/bin/ble" in hub.lines
    assert hub.lines[-1].startswith("[probe] done")
    # The capture's httpd must not outlive the probe.
    assert seen["sent"] == ["killall httpd 2>/dev/null"]


async def test_probe_says_failed_when_the_device_never_answers(monkeypatch):
    seen = _fakes(monkeypatch, None)
    hub = _Hub()
    await P._run_probe(Device(device_type="t6", petkit_id=8), "10.0.0.5", hub, {})

    assert hub.lines[-1].startswith("[probe] FAILED")
    assert not any(line.startswith("[probe] done") for line in hub.lines)
    assert seen["sent"] == ["killall httpd 2>/dev/null"]


def test_probe_command_only_reads():
    """Guard against the constant drifting into something that writes."""
    for word in ("rm ", "mv ", "mount ", "kill", "reboot", ">", "wget", "dd ", "flash"):
        assert word not in P.PROBE_COMMAND
