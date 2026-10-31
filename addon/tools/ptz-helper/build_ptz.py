#!/usr/bin/env python3
"""Generate the T6 camera-move helpers.

The device's ``ctrl`` accepts camera movement as internal message 4100
(0x1004) on ``/msg_dispatch_2``. The three 464-byte static MIPS helpers are
emitted here, byte for byte, so the shipped binaries have a reproducible
source and no vendor code is involved.

Run from the repository root::

    python3 addon/tools/ptz-helper/build_ptz.py --check
    python3 addon/tools/ptz-helper/build_ptz.py --out-dir /tmp/ptz-helpers

The helpers are data for the device; they are never executed on the host.
"""
from __future__ import annotations

import argparse
import hashlib
import struct
from pathlib import Path

BASE = 0x400000
CODE_OFF = 0x100
CODE_VA = BASE + CODE_OFF
NAME_OFF = 0x1C0
ZERO, SP, V0, A0, A1, A2, A3 = 0, 29, 2, 4, 5, 6, 7
S0, T0 = 16, 8
MQ_OPEN, MQ_TIMEDSEND, WRITE, EXIT = 4271, 4273, 4004, 4001

#: (filename, message type, direction, controller uid)
VARIANTS = (
    ("ptz_left", 0, -1, 1),
    ("ptz_right", 0, 1, 1),
    ("ptz_flip", 2, 0, 1),
)

EXPECTED_SHA256 = {
    "ptz_left": "5b24d7c2f3a7c7d25ae8255c9a7840d0fc6643e655d325456ed07714b79fe5c2",
    "ptz_right": "3561534a90f72622613aab9cac925673bb7a31eef710750cd4d777d25f47793d",
    "ptz_flip": "af59c788be0c92b4f3dcadac89689edb3d60484854e5ec2602cccb74d09c47cf",
}


def addiu(rt: int, rs: int, imm: int) -> int:
    return 0x24000000 | (rs << 21) | (rt << 16) | (imm & 0xFFFF)


def lui(rt: int, imm: int) -> int:
    return 0x3C000000 | (rt << 16) | (imm & 0xFFFF)


def ori(rt: int, rs: int, imm: int) -> int:
    return 0x34000000 | (rs << 21) | (rt << 16) | (imm & 0xFFFF)


def sw(rt: int, base: int, off: int) -> int:
    return 0xAC000000 | (base << 21) | (rt << 16) | (off & 0xFFFF)


def sb(rt: int, base: int, off: int) -> int:
    return 0xA0000000 | (base << 21) | (rt << 16) | (off & 0xFFFF)


def addu(rd: int, rs: int, rt: int) -> int:
    return (rs << 21) | (rt << 16) | (rd << 11) | 0x21


def syscall() -> int:
    return 0x0000000C


def bne(rs: int, rt: int, off: int) -> int:
    return 0x14000000 | (rs << 21) | (rt << 16) | (off & 0xFFFF)


def j(off: int) -> int:
    return 0x08000000 | (off & 0x3FFFFFF)


def build(name: str, ptype: int, pdir: int, uid: int = 1) -> bytes:
    """Return one ELF image: mq_open, mq_timedsend, write K/E, exit."""
    words: list[list[int]] = []
    labels: dict[str, int] = {}

    def emit(comment: str, word: int) -> None:
        words.append([word & 0xFFFFFFFF, 0])  # value, comment unused in output

    def label(name: str) -> None:
        labels[name] = CODE_VA + len(words) * 4

    emit("sp-=32", addiu(SP, SP, -32))
    emit("a0=name", lui(A0, BASE >> 16))
    emit("a0+=name", addiu(A0, A0, NAME_OFF))
    emit("a1=O_WRONLY", addiu(A1, ZERO, 1))
    emit("a2=0", addu(A2, ZERO, ZERO))
    emit("a3=0", addu(A3, ZERO, ZERO))
    emit("v0=mq_open", addiu(V0, ZERO, MQ_OPEN))
    emit("syscall", syscall())
    b1 = len(words)
    emit("bne a3,fail", 0)
    emit("nop", 0)
    emit("s0=fd", addu(S0, V0, ZERO))
    emit("t0=0x00071004", lui(T0, 7))
    emit("t0|=0x1004", ori(T0, T0, 0x1004))
    emit("hdr", sw(T0, SP, 0))
    emit("type", addiu(T0, ZERO, ptype))
    emit("type@4", sw(T0, SP, 4))
    emit("dir", addiu(T0, ZERO, pdir))
    emit("dir@8", sw(T0, SP, 8))
    emit("uid", addiu(T0, ZERO, uid))
    emit("uid@12", sw(T0, SP, 12))
    emit("arg5=0", sw(ZERO, SP, 16))
    emit("a0=fd", addu(A0, S0, ZERO))
    emit("a1=buf", addu(A1, SP, ZERO))
    emit("a2=16", addiu(A2, ZERO, 16))
    emit("a3=0", addu(A3, ZERO, ZERO))
    emit("v0=mq_timedsend", addiu(V0, ZERO, MQ_TIMEDSEND))
    emit("syscall", syscall())
    b2 = len(words)
    emit("bne a3,fail", 0)
    emit("nop", 0)
    emit("t0=K", addiu(T0, ZERO, 0x4B))
    emit("out0", sb(T0, SP, 20))
    emit("out1=0", sb(ZERO, SP, 21))
    j1 = len(words)
    emit("j report", 0)
    emit("nop", 0)
    label("fail")
    emit("t0=v0", addu(T0, V0, ZERO))
    emit("errno", sb(T0, SP, 21))
    emit("t0=E", addiu(T0, ZERO, 0x45))
    emit("out0=E", sb(T0, SP, 20))
    label("report")
    emit("a0=1", addiu(A0, ZERO, 1))
    emit("a1=sp+20", addiu(A1, SP, 20))
    emit("a2=2", addiu(A2, ZERO, 2))
    emit("v0=write", addiu(V0, ZERO, WRITE))
    emit("syscall", syscall())
    emit("a0=0", addu(A0, ZERO, ZERO))
    emit("v0=exit", addiu(V0, ZERO, EXIT))
    emit("syscall", syscall())

    for index in (b1, b2):
        target = labels["fail"] - (CODE_VA + index * 4 + 4)
        words[index][0] = bne(A3, ZERO, (target // 4) & 0xFFFF)
    words[j1][0] = j((labels["report"] >> 2) & 0x3FFFFFF)

    code = b"".join(struct.pack("<I", word[0]) for word in words)
    code_end = CODE_OFF + len(code)
    if code_end > NAME_OFF:
        raise ValueError(f"{name}: code overlaps the mqueue name")
    size = (max(code_end, NAME_OFF + 15) + 3) & ~3

    elf_header = struct.pack(
        "<16sHHIIIIIHHHHHH",
        b"\x7fELF\x01\x01\x01" + b"\x00" * 9,
        2, 8, 1, CODE_VA, 52, 0, 0x70001405, 52, 32, 1, 40, 0, 0,
    )
    program_header = struct.pack("<8I", 1, 0, BASE, BASE, size, size, 7, 0x1000)
    buf = bytearray(size)
    buf[0:52] = elf_header
    buf[52:84] = program_header
    buf[CODE_OFF:code_end] = code
    buf[NAME_OFF:NAME_OFF + 15] = b"msg_dispatch_2\x00"
    return bytes(buf)


def default_out_dir() -> Path:
    # <repo>/addon/tools/ptz-helper/build_ptz.py -> <repo>/addon/petkit_local/web/static/bin
    return Path(__file__).resolve().parents[2] / "petkit_local" / "web" / "static" / "bin"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out-dir", type=Path, default=default_out_dir())
    parser.add_argument("--check", action="store_true",
                        help="verify the committed helpers instead of writing them")
    args = parser.parse_args()
    out_dir: Path = args.out_dir

    if not args.check:
        out_dir.mkdir(parents=True, exist_ok=True)

    failed = False
    for name, ptype, pdir, uid in VARIANTS:
        data = build(name, ptype, pdir, uid)
        digest = hashlib.sha256(data).hexdigest()
        print(f"{name} {len(data)} {digest} type={ptype} dir={pdir} uid={uid}")
        if digest != EXPECTED_SHA256[name]:
            print(f"  ERROR: expected {EXPECTED_SHA256[name]}")
            failed = True
        if args.check:
            current = out_dir / name
            if not current.is_file() or current.read_bytes() != data:
                print(f"  ERROR: {current} differs from the generator")
                failed = True
        else:
            (out_dir / name).write_bytes(data)

    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
