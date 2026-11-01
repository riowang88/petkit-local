"""No-NFC bag patcher for the T6 / P9903 Linux programs, firmware 1.951.

The stock programs treat a missing NFC tag as "no bag". Two getters do that,
one in ``ble`` and one in ``ctrl``. This patch changes only that result, and
the ``ble`` bag-type getter so an absent tag uses the factory profile (type 1).
Infrared, fault, motion and heating checks are not in these bytes. Two ctrl checks are the exception. The screen check that records error 28
from the tag-presence byte alone is skipped. The state report's
``packageInstall`` value, which is an install time and is 0 when no tag has
been read, is reported as 1. The small panel's fault status returns "box
removed" as soon as the tag-presence byte is 0, and two later checks repeat
the spoken prompt from that same byte; those three branches are skipped.
Infrared, the other box checks, and the ``box`` field are not in these bytes.
The MCU image is not touched.

The originals are locked by the whole-file SHA-256 of the 1.951 builds this
candidate was made from. Any other build is refused, including a ``ctrl`` that
already carries the MQTT certificate patch: both patchers replace ``ctrl``,
and stacking them would hide one of the two.
"""
from __future__ import annotations

import hashlib
import logging

from petkit_local.patchers.verify import assert_mips_elf

log = logging.getLogger(__name__)

BLE_PATCHED_NAME = "ble_patched"
CTRL_PATCHED_NAME = "ctrl_nonfc"

#: Whole-file locks from the offline candidate manifest. A mismatch means this
#: is not the firmware the byte edits were checked against.
BLE_ORIGINAL_SHA256 = "806a9bcdefb1dc1e77da3cae2d611b263df94697ca90d3ccb5ef3ec2f28f11a1"
BLE_CANDIDATE_SHA256 = "f24f40daa8d4fc511d1be7cc29da8938328f3caa24785e97f4c5b8a2ca651579"
CTRL_ORIGINAL_SHA256 = "70c227e00ec0c806823c09065f1ad594336d04f9e85f40fb6bb9274db6edfdf5"
#: The first installed candidate, before the error-28 skip. An on-device file
#: with this hash is upgraded in place; it is not a valid final build.
CTRL_PREVIOUS_CANDIDATE_SHA256 = "df5b8f92aad1955bee2b5b0b55ed05721a399c84fd520d53f7369409b0d985dd"
#: Getter plus the error-28 skip, which is what a device updated before the
#: install-time report may already be running.
CTRL_ERR28_SHA256 = "f81dbdd1e9ada240b5cdd4cf7f961701fc892a38d7e96939282196cccc0f88d8"
#: Getter, error 28 and the install-time report. A device updated before the
#: panel fix may already be running this build.
CTRL_INSTALL_SHA256 = "6736a8eb25c58bc005076883c219503ba0e75025d9901075314dee05cd4a3048"
CTRL_CANDIDATE_SHA256 = "1acfac3cf478c46ec25b4a7fbce8b78ec7bf03f3c7607e1da9a969ec5fee1ffd"

#: File offsets, not virtual addresses. Each ``before`` must be present; the
#: patch never searches.
_BLE_EDITS = (
    (281808, bytes.fromhex("05000224"), bytes.fromhex("00000224")),
    (
        281752,
        bytes.fromhex("04001c3c98349c2721e099035480828f0000428c0800e003782b428c"),
        bytes.fromhex("4800033c7c39638c782b628ca42a638c010004240800e0030a108300"),
    ),
)
_CTRL_GETTER_EDITS = (
    (761316, bytes.fromhex("05000224"), bytes.fromhex("00000224")),
)
#: Two branches in pk_hmi_state_check_proc. Both record error 28 when the tag
#: presence byte is 0. The first is the startup check; the second is the
#: later "tag just disappeared" check, which also plays the prompt. Neither
#: branch is the infrared or the other box checks.
_CTRL_ERR28_EDITS = (
    # bnez presence, skip  ->  always skip the startup pk_set_err_type(28)
    (0xaebf4, bytes.fromhex("04004014"), bytes.fromhex("04000010")),
    # bnez previous, set-error  ->  always jump to the cache update, so a
    # missing tag neither records 28 nor plays the "box installed" prompt.
    (0xaecf8, bytes.fromhex("44014014"), bytes.fromhex("17000010")),
)
#: web_get_state_cjson loads this word and sends it as packageInstall. A saved
#: report from a tagged bag carries a unix time here; 0 is what the app shows
#: as the box having been taken out. The following instructions reload gp and
#: clear the high half of the number, and stay as they are.
_CTRL_INSTALL_EDITS = (
    (0x3b3b4, bytes.fromhex("1c01068e"), bytes.fromhex("01000624")),
)
#: pk_hmi_get_dev_fault_error_sta returns panel code 2 as soon as the tag
#: presence byte is 0. That code is the small panel's "box removed" page.
#: The branch now always continues into the bag-state getter and the other
#: box checks. Two later branches play or re-arm the spoken prompt from that
#: same byte; each now continues at the next box check instead.
_CTRL_PANEL_EDITS = (
    (0xa7ce8, bytes.fromhex("03004014"), bytes.fromhex("03000010")),
    (0xad2b0, bytes.fromhex("07006010"), bytes.fromhex("01000010")),
    (0xae9c0, bytes.fromhex("0c006010"), bytes.fromhex("01000010")),
    (0xaea50, bytes.fromhex("08006010"), bytes.fromhex("01000010")),
)
_CTRL_EDITS = _CTRL_GETTER_EDITS + _CTRL_ERR28_EDITS + _CTRL_INSTALL_EDITS + _CTRL_PANEL_EDITS


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _apply_edits(data: bytes, *, name: str, edits: tuple) -> bytes:
    """Replace the locked windows. The file size does not change."""
    patched = bytearray(data)
    for offset, before, after in edits:
        if len(before) != len(after):
            raise RuntimeError(f"{name} edit at {offset} changes the file size")
        window = bytes(patched[offset:offset + len(before)])
        if window != before:
            raise ValueError(
                f"{name} bytes at offset {offset} are {window.hex()}, "
                f"not the locked {before.hex()}")
        patched[offset:offset + len(after)] = after
    out = bytes(patched)
    if len(out) != len(data):
        raise RuntimeError(f"{name} size changed: {len(data)} -> {len(out)}")
    return out


def _finish(data: bytes, out: bytes, *, name: str, candidate_sha256: str) -> bytes:
    if _sha256(out) != candidate_sha256:
        raise RuntimeError(f"{name} patched SHA-256 is not the locked candidate")
    log.info("Patched %s for no-NFC (md5 %s -> %s)", name,
             hashlib.md5(data).hexdigest(), hashlib.md5(out).hexdigest())
    return out


def patch_ble(data: bytes) -> bytes:
    """Return the no-NFC ``ble``. An already-patched file is returned unchanged."""
    assert_mips_elf(data, "ble", exec_only=True)
    if _sha256(data) == BLE_CANDIDATE_SHA256:
        return data
    if _sha256(data) != BLE_ORIGINAL_SHA256:
        raise ValueError(
            f"ble SHA-256 {_sha256(data)} is not the locked 1.951 original")
    out = _apply_edits(data, name="ble", edits=_BLE_EDITS)
    return _finish(data, out, name="ble", candidate_sha256=BLE_CANDIDATE_SHA256)


def patch_ctrl_nfc(data: bytes) -> bytes:
    """Return the no-NFC ``ctrl``.

    Accepts the locked 1.951 original and each earlier candidate in this
    series. Anything else is refused. An up-to-date file is returned unchanged.
    """
    assert_mips_elf(data, "ctrl", exec_only=True)
    got = _sha256(data)
    if got == CTRL_CANDIDATE_SHA256:
        return data
    if got == CTRL_ORIGINAL_SHA256:
        edits = _CTRL_EDITS
    elif got == CTRL_PREVIOUS_CANDIDATE_SHA256:
        edits = _CTRL_ERR28_EDITS + _CTRL_INSTALL_EDITS + _CTRL_PANEL_EDITS
    elif got == CTRL_ERR28_SHA256:
        edits = _CTRL_INSTALL_EDITS + _CTRL_PANEL_EDITS
    elif got == CTRL_INSTALL_SHA256:
        edits = _CTRL_PANEL_EDITS
    else:
        raise ValueError(
            f"ctrl SHA-256 {got} is not the locked 1.951 original")
    out = _apply_edits(data, name="ctrl", edits=edits)
    return _finish(data, out, name="ctrl", candidate_sha256=CTRL_CANDIDATE_SHA256)


PATCHER_INFO = {
    "id": "no_nfc",
    "name": "No-NFC bag",
    "description": (
        "Lets this T6 use a bag that has no NFC tag, by changing only the "
        "two Linux programs that turn a missing tag into \"no bag\". The bag "
        "type used in that case is the factory profile (1). A tag that is "
        "present keeps the original result.\n\n"
        "Infrared, motion and heating checks are not modified, and the "
        "separate MCU firmware is not modified. The screen check that records "
        "error 28 (bag box removed) from a missing tag alone is skipped. The "
        "small panel and its repeated spoken prompt no longer treat a missing "
        "tag by itself as the box having been taken out. The reported install "
        "time is 1 when no tag has been read. The other box checks and the "
        "box field are not changed.\n\n"
        "Only firmware 1.951 with the locked ble and ctrl hashes is accepted. "
        "Anything else, including a ctrl already patched for MQTT, is refused. "
        "The MQTT patch also replaces ctrl, so the two cannot be installed "
        "together.\n\n"
        "What it does: copies /app/bin/ble and /app/bin/ctrl to writable "
        "storage, applies the locked byte edits, then updates the boot "
        "wrapper to bind-mount both before the stock init starts them. "
        "The device reboots. Removing the patch deletes those copies and "
        "boots the stock programs again."
    ),
    "files": [BLE_PATCHED_NAME, CTRL_PATCHED_NAME],
    "arch": "mips",
    # ble is 463,592 bytes and ctrl is 1,575,524. The edits do not change size.
    "needs_bytes": 3145728,
}
