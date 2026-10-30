"""The Purobot Ultra's privacy ("home") mode settings and its camera position.

PetKit's app has a privacy mode on the T6 that can, among other things, turn
the camera inward. T6 firmware 951's `parse_recv_property_set_normal` looks up
`homeMode`, `cameraOff`, `cameraInward` and `noSound` and reads each as cJSON
`valueint` -- so the value must be a JSON NUMBER, the opposite of `timezone` on
the same parser. What any of them does on the hardware is not verified, which
is why none is seeded: an invented default would be served back to the box as
its owner's setting.
"""
import json

import pytest
from aiohttp.test_utils import TestClient, TestServer

from petkit_local.devices import defaults
from petkit_local.devices.base import Device
from petkit_local.devices.ble import BLERegistry
from petkit_local.devices.payloads import to_device_info
from petkit_local.devices.registry import DeviceRegistry
from petkit_local.devices.state_parsers import normalize_property_params, parse_state_report
from petkit_local.events import codes
from petkit_local.ha.categories import get_entities_for_device, get_setting_fields
from petkit_local.ha.commands import handle_ha_command
from petkit_local.http.handlers.heartbeat import MSG_TYPE_PROPERTY_SET, _to_heartbeat_content
from petkit_local.utils.const import DEVICE_TYPES_ALL
from petkit_local.web.hub import EventHub
from petkit_local.web.panel import create_panel_app

#: entity key -> the wire name it writes.
HOME_MODE_SWITCHES = {
    "home_mode": "homeMode",
    "camera_inward": "cameraInward",
    "camera_off": "cameraOff",
    "no_sound": "noSound",
}


def _t6():
    d = Device(device_type="t6", petkit_id=6, serial_number="SN6")
    d.config.setdefault("settings", defaults.default_settings(d))
    return d


def _by_key(device):
    return {e.key: e for e in get_entities_for_device(device)}


# --- which models publish them ---

def test_the_t6_publishes_the_four_privacy_mode_switches():
    ents = _by_key(_t6())
    for key, field in HOME_MODE_SWITCHES.items():
        e = ents[key]
        assert e.component == "switch"
        assert e.value_path == f"settings.{field}"
        assert e.setting_field == field


def test_the_privacy_mode_switches_do_not_replace_the_camera_and_microphone_ones():
    """`cameraOff`/`noSound` are sub-options of privacy mode, stored in their
    own firmware slots; `camera`/`microphone` are the everyday switches."""
    ents = _by_key(_t6())
    assert ents["camera"].value_path == "settings.camera"
    assert ents["microphone"].value_path == "settings.microphone"
    assert ents["camera_off"].value_path != ents["camera"].value_path
    assert ents["no_sound"].value_path != ents["microphone"].value_path


@pytest.mark.parametrize("device_type", sorted(DEVICE_TYPES_ALL - {"t6"}))
def test_no_other_model_publishes_them(device_type):
    keys = set(_by_key(Device(device_type=device_type, petkit_id=1, serial_number="SN")))
    assert not keys & {*HOME_MODE_SWITCHES, "camera_position"}, device_type


def test_the_settings_sync_accepts_them_from_the_device():
    """A property/post that does carry one records the device's real value."""
    assert set(HOME_MODE_SWITCHES.values()) <= get_setting_fields(_t6())


# --- what a write sends ---

@pytest.mark.parametrize("key,field", sorted(HOME_MODE_SWITCHES.items()))
def test_a_write_is_one_property_set_with_an_integer(key, field):
    d = _t6()
    for payload, expected in (("ON", 1), ("OFF", 0)):
        suffix, env = handle_ha_command(d, _by_key(d)[key], payload)
        assert suffix == "property/set"
        assert env["method"] == "thing.service.property.set"
        # Exactly one field: a `homeMode` the firmware declines returns out of
        # the whole parser, so anything sent beside it would be lost with it.
        assert env["params"] == {field: expected}
        assert type(env["params"][field]) is int, "valueint: a bool or string is not it"
        assert f'"{field}": {expected}' in json.dumps(env), "a JSON number, never a string"
        assert d.config["settings"][field] == expected


def test_over_http_it_arrives_as_a_property_set_heartbeat():
    d = _t6()
    _, env = handle_ha_command(d, _by_key(d)["camera_inward"], "ON")
    content = json.loads(_to_heartbeat_content({**env, "_service_suffix": "property/set"}))
    assert content["msgType"] == MSG_TYPE_PROPERTY_SET
    assert content["payload"] == {"cameraInward": 1}
    assert type(content["payload"]["cameraInward"]) is int


# --- nothing is invented ---

def test_none_is_seeded():
    seeded = defaults.default_settings(_t6())
    assert not seeded.keys() & set(HOME_MODE_SWITCHES.values())


def test_dev_device_info_carries_none_until_the_owner_sets_one():
    d = _t6()
    served = to_device_info(d)["result"]["settings"]
    assert not served.keys() & set(HOME_MODE_SWITCHES.values())

    handle_ha_command(d, _by_key(d)["home_mode"], "ON")
    served = to_device_info(d)["result"]["settings"]
    assert served["homeMode"] == 1
    assert not served.keys() & {"cameraInward", "cameraOff", "noSound"}, (
        "setting one must not fill in the others")


# --- the protocol table ---

def test_the_protocol_table_names_every_field_a_switch_writes():
    table = codes.LITTER_T6_HOME_MODE_SET_FIELDS
    assert set(table) == set(HOME_MODE_SWITCHES.values())
    for field, (meaning, grade) in table.items():
        assert meaning, field
        assert grade in codes.GRADES, field


def test_camera_position_is_recorded_as_unverified():
    meaning, grade = codes.LITTER_T6_STATE_FIELDS["cameraPosition"]
    assert grade == codes.UNVERIFIED
    assert "raw" in meaning


# --- cameraPosition, read only ---

def test_camera_position_is_a_read_only_raw_sensor():
    e = _by_key(_t6())["camera_position"]
    assert e.component == "sensor"
    assert e.value_path == "state.cameraPosition"
    assert not e.is_settable
    assert not e.options, "the values are not decoded, so nothing may label them"
    assert not e.unit


def test_both_transports_read_camera_position():
    report = {"litter": {"percent": 50}, "cameraPosition": 2}
    assert parse_state_report("t6", report)["cameraPosition"] == 2
    assert normalize_property_params("t6", report)["cameraPosition"] == 2
    # Zero is a value, not an absence.
    assert parse_state_report("t6", {**report, "cameraPosition": 0})["cameraPosition"] == 0


def test_a_report_without_camera_position_says_nothing_about_it():
    """Unknown until reported -- never a 0 nobody sent -- and a later report
    that omits it leaves the last reported position standing: it is a position,
    not one of the presence-signalled flags."""
    bare = {"litter": {"percent": 50}}
    assert "cameraPosition" not in parse_state_report("t6", bare)
    assert "cameraPosition" not in normalize_property_params("t6", bare)

    d = _t6()
    d.state.update(parse_state_report("t6", {**bare, "cameraPosition": 2}))
    d.state.update(parse_state_report("t6", bare))
    d.state.update(normalize_property_params("t6", bare))
    assert d.state["cameraPosition"] == 2


# --- the panel ---

async def test_the_panel_shows_them_unset_and_queues_a_write_for_the_heartbeat():
    reg = DeviceRegistry()
    reg.get_or_create(petkit_id=1, device_type="t6", serial_number="SN6")
    app = create_panel_app(reg, BLERegistry(), EventHub(),
                           {"api_url": "http://x/6/", "mqtt_tls": True,
                            "mqtt_tls_port": 443, "capture": False,
                            "capture_dir": "/nope"}, None)
    c = TestClient(TestServer(app))
    await c.start_server()
    try:
        detail = await (await c.get("/api/devices/1")).json()
        ents = {e["key"]: e for e in detail["entities"]}
        for key in (*HOME_MODE_SWITCHES, "camera_position"):
            assert ents[key]["value"] is None, f"{key} must read unknown, not 0"

        r = await c.post("/api/devices/1/command",
                         data=json.dumps({"entity": "camera_inward", "value": "ON"}))
        out = await r.json()
        assert r.status == 200 and out["delivered"] == "heartbeat-queue"
        queued = reg.get(1).command_queue[-1]
        assert queued["_service_suffix"] == "property/set"
        assert queued["params"] == {"cameraInward": 1}
    finally:
        await c.close()
