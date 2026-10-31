# T6 camera-move helper source

`build_ptz.py` is the source generator for the three 464-byte MIPS helpers:

| helper | message 4100 payload | use |
|---|---|---|
| `ptz_left` | `type=0, dir=-1, uid=1` | one small step left |
| `ptz_right` | `type=0, dir=+1, uid=1` | one small step right |
| `ptz_flip` | `type=2, dir=0, uid=1` | 180° flip |

The backend action code that stages and runs them is `petkit_local/ptz.py` and
`ha/commands.py::_ptz`. A press of the left/right button runs the helper five
times; flip is one shot.

## Build / verify

From the repository root:

```sh
python3 addon/tools/ptz-helper/build_ptz.py --check
python3 addon/tools/ptz-helper/build_ptz.py --out-dir /tmp/ptz-helpers
```

`--check` verifies both the expected SHA256 and the committed files under
`petkit_local/web/static/bin/`. The test suite also runs this check
(`tests/test_ptz_helpers.py`).

Expected hashes:

```text
ptz_left   5b24d7c2f3a7c7d25ae8255c9a7840d0fc6643e655d325456ed07714b79fe5c2
ptz_right  3561534a90f72622613aab9cac925673bb7a31eef710750cd4d777d25f47793d
ptz_flip   af59c788be0c92b4f3dcadac89689edb3d60484854e5ec2602cccb74d09c47cf
```

## Notes

- These are hand-emitted MIPS32 images, not vendor code and not the output of a
  compiler. They call `mq_open("msg_dispatch_2")` and `mq_timedsend()` with the
  firmware's internal message id 4100.
- The mqueue name must not have a leading slash: the raw syscall path differs
  from glibc's `mq_open`, which strips it.
- The helpers are data for the device. Never execute them on the host.
- The binaries are shipped to the device through the existing
  `/patcher/download` route and removed from `/tmp` after the run.
