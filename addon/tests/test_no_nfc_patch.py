"""The no-NFC patcher locks the T6 1.951 originals and installs both programs."""
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock

import pytest

from petkit_local.devices.base import Device
from petkit_local.patchers.common import generate_app_init_wrapper, get_staged_path
from petkit_local.patchers.no_nfc import (
    BLE_CANDIDATE_SHA256, BLE_ORIGINAL_SHA256, BLE_PATCHED_NAME,
    CTRL_CANDIDATE_SHA256, CTRL_ORIGINAL_SHA256, CTRL_PATCHED_NAME,
    patch_ble, patch_ctrl_nfc,
)

_STOCK = Path(__file__).parent / "firmware" / "t6-1.951"


def test_wrapper_mounts_both_programs_before_stock_init():
    w = generate_app_init_wrapper({"no_nfc", "cloud", "cacert", "camera", "ssh"})
    assert "mount --bind /system/ble_patched /app/bin/ble" in w
    assert "mount --bind /system/ctrl_nonfc /app/bin/ctrl" in w
    assert "mount --bind /system/cloud_patched /app/bin/cloud" in w
    assert "mount --bind /system/ca_patched.crt /app/bin/ca.crt" in w
    assert "mount --bind /app/bin/tserver /app/bin/agora" in w
    assert "/system/dropbear" in w
    lines = w.split("\n")
    stock = next(i for i, line in enumerate(lines) if ". /app/script/app_init.sh" in line)
    ble = next(i for i, line in enumerate(lines) if "ble_patched" in line)
    ctrl = next(i for i, line in enumerate(lines) if "ctrl_nonfc" in line)
    assert ble < stock and ctrl < stock


def test_no_nfc_ctrl_mount_comes_after_the_mqtt_ctrl_mount():
    w = generate_app_init_wrapper({"mqtt", "no_nfc"})
    lines = [line for line in w.split("\n") if "mount --bind" in line and "/app/bin/ctrl" in line]
    assert lines[0].endswith("/system/ctrl_patched /app/bin/ctrl")
    assert lines[1].endswith("/system/ctrl_nonfc /app/bin/ctrl")


def test_a_random_file_is_refused():
    with pytest.raises(ValueError):
        patch_ble(b"\x7fELF" + b"\x00" * 2000)


def _sha(path: Path) -> str:
    import hashlib
    return hashlib.sha256(path.read_bytes()).hexdigest()


@pytest.mark.skipif(not (_STOCK / "ble").is_file(), reason="T6 1.951 ble is not on this machine")
def test_stock_ble_patches_to_the_locked_candidate():
    original = (_STOCK / "ble").read_bytes()
    assert _sha(_STOCK / "ble") == BLE_ORIGINAL_SHA256
    patched = patch_ble(original)
    assert _sha_bytes(patched) == BLE_CANDIDATE_SHA256
    assert len(patched) == len(original)
    with pytest.raises(ValueError, match="already"):
        patch_ble(patched)


@pytest.mark.skipif(not (_STOCK / "ctrl").is_file(), reason="T6 1.951 ctrl is not on this machine")
def test_stock_ctrl_patches_to_the_locked_candidate():
    original = (_STOCK / "ctrl").read_bytes()
    assert _sha(_STOCK / "ctrl") == CTRL_ORIGINAL_SHA256
    patched = patch_ctrl_nfc(original)
    assert _sha_bytes(patched) == CTRL_CANDIDATE_SHA256
    assert len(patched) == len(original)
    with pytest.raises(ValueError, match="already"):
        patch_ctrl_nfc(patched)


def _sha_bytes(data: bytes) -> str:
    import hashlib
    return hashlib.sha256(data).hexdigest()


async def test_apply_refuses_while_mqtt_is_active_and_writes_nothing():
    from petkit_local.web.api import patchers as P

    d = Device(device_type="t6", petkit_id=10000001, serial_number="SN")
    d.config["active_patchers"] = ["mqtt"]
    hub = MagicMock()
    send = AsyncMock()
    # The import inside the module is the name the apply function calls.
    import petkit_local.web.api.patchers as live
    live_send = live.send_run_cmd
    live.send_run_cmd = send
    try:
        await P._patcher_apply(d, "no_nfc", "192.0.2.10",
                               "http://host/patcher/download/10000001", hub, {"registry": MagicMock()})
    finally:
        live.send_run_cmd = live_send
    assert "no_nfc" not in d.config.get("active_patchers", [])
    send.assert_not_called()
    assert any("MQTT" in str(call) for call in hub.publish.call_args_list)


async def test_apply_uploads_both_files_and_keeps_the_other_patchers(monkeypatch):
    from petkit_local.web.api import patchers as P

    monkeypatch.setattr(P, "detect_patch_storage_dir", AsyncMock(return_value="/system"))
    monkeypatch.setattr(P, "ensure_space_for", AsyncMock(return_value="space ok"))
    monkeypatch.setattr(P, "send_run_cmd", AsyncMock(return_value="heartbeat"))
    monkeypatch.setattr(P, "wait_for_heartbeat", AsyncMock(return_value=True))
    monkeypatch.setattr(P, "download_from_device", AsyncMock(side_effect=[b"B" * 2048, b"C" * 2048]))
    monkeypatch.setattr(P, "patch_ble", lambda data: b"ble-patched")
    monkeypatch.setattr(P, "patch_ctrl_nfc", lambda data: b"ctrl-patched")
    monkeypatch.setattr(P.asyncio, "sleep", AsyncMock())

    d = Device(device_type="t6", petkit_id=10000001, serial_number="SN")
    d.config["active_patchers"] = ["cloud", "cacert", "camera", "ssh"]
    staged: dict[str, bytes] = {}
    real_stage = P.stage_file

    def _stage(name, data, device_id):
        staged[name] = data
        return real_stage(name, data, device_id)

    monkeypatch.setattr(P, "stage_file", _stage)
    app = {"registry": MagicMock(), "bridge": None, "cfg": {}}
    await P._patcher_apply(d, "no_nfc", "192.0.2.10",
                           "http://192.0.2.1/patcher/download/10000001", MagicMock(), app)
    wrapper = staged["app_init.sh"].decode()
    assert "mount --bind /system/ble_patched /app/bin/ble" in wrapper
    assert "mount --bind /system/ctrl_nonfc /app/bin/ctrl" in wrapper
    assert "mount --bind /system/cloud_patched /app/bin/cloud" in wrapper
    assert "/system/dropbear" in wrapper

    assert set(d.config["active_patchers"]) == {"cacert", "camera", "cloud", "no_nfc", "ssh"}
    assert get_staged_path(BLE_PATCHED_NAME, 10000001) is None
    assert get_staged_path(CTRL_PATCHED_NAME, 10000001) is None
    uploaded = " ".join(str(call.args[1]) for call in P.send_run_cmd.await_args_list)
    assert "/system/ble_patched" in uploaded
    assert "/system/ctrl_nonfc" in uploaded
    wrapper = next(call.args[1] for call in P.send_run_cmd.await_args_list
                   if "app_init.sh" in call.args[1])
    assert "wget" in wrapper and "reboot" in uploaded
