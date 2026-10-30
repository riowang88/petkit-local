"""One-shot camera-move helpers for the T6.

The device's ``ctrl`` receives camera movement as an internal message 4100 on
``/msg_dispatch_2`` (``{type, dir, uid}``, 12 bytes).  The Linux userland cannot
write a POSIX mqueue remotely, so the add-on stages one of three tiny MIPS
helpers and asks the device to fetch and run it with ``run_cmd``:

* ``ptz_left``  — ``type=0, dir=-1`` (one small step left)
* ``ptz_right`` — ``type=0, dir=+1`` (one small step right)
* ``ptz_flip``  — ``type=2, dir=0`` (the 180° flip)

The helper is served by the existing ``/patcher/download/{device}/{filename}``
route, i.e. through the same NAS entry the device already polls.  No new LAN
listener is opened and nothing is written to persistent device storage: the
command fetches to ``/tmp``, runs, and deletes the file.

Verified on T6 firmware 951 and 929.  The helpers are statically linked MIPS32
binaries; they are data for the device and are never executed on the host.
"""
from __future__ import annotations

import asyncio
import logging
from pathlib import Path
from urllib.parse import urlparse

from petkit_local.patchers.common import cleanup_staged, stage_file

log = logging.getLogger(__name__)

KINDS = ("left", "right", "flip")
_HELPER_DIR = Path(__file__).resolve().parent / "web" / "static" / "bin"
_download_base: str | None = None


def configure(api_url: str) -> None:
    """Record the device-facing base URL used by ``/patcher/download``.

    Called once from wiring with ``Config.api_url``; the patchers build the
    same URL from it.  A missing host leaves the feature disabled rather than
    guessing an address.
    """
    global _download_base
    parsed = urlparse(api_url or "")
    if not parsed.hostname:
        _download_base = None
        return
    port = parsed.port or (443 if parsed.scheme == "https" else 80)
    _download_base = f"{parsed.scheme or 'http'}://{parsed.hostname}:{port}/patcher/download"


def filename_for(kind: str) -> str:
    if kind not in KINDS:
        raise ValueError(f"unknown camera move {kind!r}")
    return f"ptz_{kind}"


def stage(kind: str, device_id: int) -> str:
    """Stage the helper for one device and return its filename."""
    filename = filename_for(kind)
    data = (_HELPER_DIR / filename).read_bytes()
    stage_file(filename, data, device_id)
    return filename


def staged_url(filename: str, device_id: int) -> str:
    if _download_base is None:
        raise RuntimeError("api_url is not configured")
    return f"{_download_base}/{device_id}/{filename}"


def release_later(filename: str, device_id: int, delay: float = 180.0) -> None:
    """Drop the staged copy after the device has had time to fetch it.

    The command is queued at most until the next heartbeat, so a few minutes is
    ample.  Without a running loop (unit tests) the caller keeps the file; it is
    464 bytes and the next run overwrites it.
    """
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return
    loop.call_later(delay, cleanup_staged, filename, device_id)
