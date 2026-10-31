"""The shipped MIPS helpers must be reproducible from the checked-in source."""
from __future__ import annotations

import hashlib
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
SCRIPT = REPO / "tools" / "ptz-helper" / "build_ptz.py"
BIN_DIR = REPO / "petkit_local" / "web" / "static" / "bin"

EXPECTED = {
    "ptz_left": "5b24d7c2f3a7c7d25ae8255c9a7840d0fc6643e655d325456ed07714b79fe5c2",
    "ptz_right": "3561534a90f72622613aab9cac925673bb7a31eef710750cd4d777d25f47793d",
    "ptz_flip": "af59c788be0c92b4f3dcadac89689edb3d60484854e5ec2602cccb74d09c47cf",
}


def test_generator_reproduces_the_committed_helpers(tmp_path):
    result = subprocess.run(
        [sys.executable, str(SCRIPT), "--out-dir", str(tmp_path)],
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert result.returncode == 0, result.stdout + result.stderr

    for name, expected in EXPECTED.items():
        built = (tmp_path / name).read_bytes()
        committed = (BIN_DIR / name).read_bytes()
        assert len(built) == 464
        assert hashlib.sha256(built).hexdigest() == expected
        assert built == committed, f"{name} in the repo is not the generator's output"


def test_check_mode_accepts_the_repository_files():
    result = subprocess.run(
        [sys.executable, str(SCRIPT), "--check"],
        check=False,
        capture_output=True,
        text=True,
        timeout=30,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert "ERROR" not in result.stdout
