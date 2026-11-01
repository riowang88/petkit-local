"""No-NFC bag patcher for the T6 / P9903 Linux programs, firmware 1.951.

The stock programs treat a missing NFC tag as "no bag". Two getters do that,
one in ``ble`` and one in ``ctrl``. This patch changes only that result, and
the ``ble`` bag-type getter so an absent tag uses the factory profile (type 1).
Infrared, fault, motion and heating checks are not in these bytes. The MCU
image is not touched.

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
CTRL_CANDIDATE_SHA256 = "df5b8f92aad1955bee2b5b0b55ed05721a399c84fd520d53f7369409b0d985dd"

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
_CTRL_EDITS = (
    (761316, bytes.fromhex("05000224"), bytes.fromhex("00000224")),
)


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _apply_locked(data: bytes, *, name: str, original_sha256: str,
                  candidate_sha256: str, edits: tuple) -> bytes:
    """Apply the locked edits, or refuse.

    Raises:
        ValueError: The file is already the candidate, or it is not the locked
            original, or a locked window is not the expected instruction.
    """
    assert_mips_elf(data, name, exec_only=True)
    got = _sha256(data)
    if got == candidate_sha256:
        raise ValueError(f"{name} is already the no-NFC build")
    if got != original_sha256:
        raise ValueError(
            f"{name} SHA-256 {got} is not the locked 1.951 original {original_sha256}")
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
    if _sha256(out) != candidate_sha256:
        raise RuntimeError(f"{name} patched SHA-256 is not the locked candidate")
    log.info("Patched %s for no-NFC (md5 %s -> %s)", name, 
             hashlib.md5(data).hexdigest(), hashlib.md5(out).hexdigest())
    return out


def patch_ble(data: bytes) -> bytes:
    """Return the no-NFC ``ble``. Refuses anything except the locked original."""
    return _apply_locked(
        data, name="ble", original_sha256=BLE_ORIGINAL_SHA256,
        candidate_sha256=BLE_CANDIDATE_SHA256, edits=_BLE_EDITS)


def patch_ctrl_nfc(data: bytes) -> bytes:
    """Return the no-NFC ``ctrl``. Refuses anything except the locked original."""
    return _apply_locked(
        data, name="ctrl", original_sha256=CTRL_ORIGINAL_SHA256,
        candidate_sha256=CTRL_CANDIDATE_SHA256, edits=_CTRL_EDITS)


PATCHER_INFO = {
    "id": "no_nfc",
    "name": "No-NFC bag",
    "description": (
        "Lets this T6 use a bag that has no NFC tag, by changing only the "
        "two Linux programs that turn a missing tag into \"no bag\". The bag "
        "type used in that case is the factory profile (1). A tag that is "
        "present keeps the original result.\n\n"
        "Infrared, fault, motion and heating checks are not modified, and "
        "the separate MCU firmware is not modified.\n\n"
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
