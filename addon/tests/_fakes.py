"""Stand-ins for the two MQTT objects several test modules need.

Not fixtures: these are instantiated ad hoc, sometimes more than once per test,
and assigned onto a publisher or a bridge. A module here rather than
`conftest.py` because importing from a conftest is a pytest anti-pattern — that
file is for fixtures pytest collects, not for a helper library.
"""
from __future__ import annotations

from typing import Any


class FakeMqttClient:
    """Records every publish instead of sending it.

    Stands in for the aiomqtt client that `HAPublisher` and `MQTTBridge` each
    hold one of. `published` is `(topic, payload, kwargs)` per call, in order.
    """

    def __init__(self) -> None:
        self.published: list[tuple[str, Any, dict[str, Any]]] = []

    async def publish(self, topic: str, payload: Any, **kw: Any) -> None:
        self.published.append((topic, payload, kw))


class FakeMessage:
    """One inbound MQTT message, shaped as aiomqtt hands it over."""

    def __init__(self, topic: Any, payload: Any) -> None:
        self.topic = topic
        self.payload = payload


class EchoingDevice:
    """Stands in for the device's storage in a patcher apply/remove flow.

    Wraps `stage_file` to remember every file the flow staged, and answers the
    `md5sum` read-back as a device would if each download landed: the md5 of the
    staged file whose name is the path's basename. Name a basename in `lost` and
    its read-back comes back empty, the way it does when the file never arrived.
    """

    def __init__(self, module, monkeypatch):
        import hashlib
        import os
        from unittest.mock import AsyncMock

        self.staged: dict[str, bytes] = {}
        self.lost: set[str] = set()
        real_stage = module.stage_file

        def stage(name, data, device_id):
            self.staged[name] = data
            return real_stage(name, data, device_id)

        async def capture(device, device_ip, command, **kwargs):
            lines = []
            for path in command.split()[1:]:
                name = os.path.basename(path)
                if name in self.staged and name not in self.lost:
                    lines.append(f"{hashlib.md5(self.staged[name]).hexdigest()}  {path}")
            return "\n".join(lines)

        monkeypatch.setattr(module, "stage_file", stage)
        monkeypatch.setattr(module, "run_cmd_capture", AsyncMock(side_effect=capture))
