"""Secrets a device puts in a query string must not reach a log.

A T6 polls `dev_package_auth?sn=...&secret=...` every minute, and aiohttp's
access log writes the whole request line verbatim — so the consumable secret
landed on disk hundreds of times a day. Capture files stay verbatim on purpose
(`utils/capture.py`); this covers the process log and the panel's live log.
"""
from __future__ import annotations

import logging

from aiohttp.test_utils import TestClient, TestServer

from petkit_local.devices.registry import DeviceRegistry
from petkit_local.http.server import create_app
from petkit_local.main import configure_logging
from petkit_local.utils.logredact import (
    REDACTED,
    RedactSecretsFilter,
    redact_query,
    redact_text,
)
from petkit_local.web.hub import EventHub

SECRET = "s3cr3tValue0123456789"
HDR = {"X-Device": "id=100&sn=SN100&type=T6"}


def _remove_filter(handlers):
    for h in handlers:
        for f in list(h.filters):
            if isinstance(f, RedactSecretsFilter):
                h.removeFilter(f)


def test_the_secret_in_a_request_line_is_redacted_and_the_rest_is_kept():
    line = f'"GET /6/t6/dev_package_auth?sn=SN1&secret={SECRET} HTTP/1.1" 200'
    out = redact_text(line)
    assert SECRET not in out
    assert f"secret={REDACTED}" in out
    assert "/6/t6/dev_package_auth?sn=SN1&" in out
    assert out.endswith(' HTTP/1.1" 200')


def test_every_sensitive_name_is_redacted_case_insensitively():
    line = "?Secret=a1&sign=b2&TOKEN=c3&password=d4&pwd=e5&key=f6&sn=keep"
    out = redact_text(line)
    for value in ("a1", "b2", "c3", "d4", "e5", "f6"):
        assert value not in out
    assert out.count(REDACTED) == 6
    assert "sn=keep" in out


def test_only_whole_parameter_names_match():
    line = "/x?keyword=one&monkey=two&secretary=three&heap=0"
    assert redact_text(line) == line


def test_a_query_dict_is_redacted_without_touching_other_keys():
    q = {"sn": "SN1", "secret": SECRET, "Sign": "abc"}
    assert redact_query(q) == {"sn": "SN1", "secret": REDACTED, "Sign": REDACTED}
    assert q["secret"] == SECRET, "the caller's dict is not mutated"


def test_the_filter_rewrites_a_record_formatted_from_args():
    rec = logging.LogRecord("aiohttp.access", logging.INFO, __file__, 1,
                            '%s "%s"', ("127.0.0.1", f"GET /a?secret={SECRET} HTTP/1.1"), None)
    assert RedactSecretsFilter().filter(rec) is True
    assert SECRET not in rec.getMessage()
    assert f"secret={REDACTED}" in rec.getMessage()


def test_configure_logging_puts_the_filter_on_every_root_handler_once():
    root = logging.getLogger()
    try:
        configure_logging("INFO")
        configure_logging("INFO")
        assert root.handlers
        for h in root.handlers:
            assert sum(isinstance(f, RedactSecretsFilter) for f in h.filters) == 1
    finally:
        _remove_filter(root.handlers)


async def test_the_access_log_of_a_real_request_carries_no_secret(caplog):
    caplog.set_level(logging.INFO, logger="aiohttp.access")
    root = logging.getLogger()
    configure_logging("INFO")
    client = TestClient(TestServer(create_app(DeviceRegistry(), {"capture": False})))
    await client.start_server()
    try:
        r = await client.get(f"/6/t6/dev_package_auth?sn=SN1&secret={SECRET}", headers=HDR)
        assert r.status == 200
    finally:
        await client.close()
        access = [rec for rec in caplog.records if rec.name == "aiohttp.access"]
        _remove_filter(root.handlers)
    assert access, "the access log line was emitted"
    assert SECRET not in caplog.text
    assert f"secret={REDACTED}" in caplog.text
    assert "sn=SN1" in caplog.text


async def test_the_panel_live_log_shows_the_query_without_the_secret():
    app = create_app(DeviceRegistry(), {"capture": False})
    hub = EventHub()
    app["event_hub"] = hub
    client = TestClient(TestServer(app))
    await client.start_server()
    try:
        await client.get(f"/6/t6/dev_package_auth?sn=SN1&secret={SECRET}", headers=HDR)
    finally:
        await client.close()
    http = [e for e in hub.recent() if e.get("kind") == "http"]
    assert http
    query = http[-1]["detail"]["query"]
    assert query == {"sn": "SN1", "secret": REDACTED}
