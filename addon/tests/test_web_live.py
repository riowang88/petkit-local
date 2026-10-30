"""Viewer-only live MP4 proxy, exercised against a real local upstream."""
import asyncio

import pytest
from aiohttp import WSMsgType, WSServerHandshakeError, web
from aiohttp.test_utils import TestClient, TestServer

from petkit_local.devices.registry import DeviceRegistry
from petkit_local.media import go2rtc
from petkit_local.web.hub import EventHub
from petkit_local.web.panel import create_panel_app


@pytest.fixture
async def viewer():
    registry = DeviceRegistry()
    camera = registry.get_or_create(10000001, "t6")
    camera.state["streamAvailable"] = True
    registry.get_or_create(2, "t4")
    app = create_panel_app(registry, None, EventHub(), {})
    client = TestClient(TestServer(app, shutdown_timeout=0.2))
    await client.start_server()
    try:
        yield client, camera
    finally:
        await client.close()


async def upstream(handler, monkeypatch, path="/api/stream.mp4"):
    app = web.Application()
    app.router.add_get(path, handler)
    server = TestServer(app, shutdown_timeout=0.2)
    await server.start_server()
    monkeypatch.setattr(go2rtc, "API_ADDR", f"127.0.0.1:{server.port}")
    return server


async def test_unknown_device_is_not_a_stream(viewer):
    client, _ = viewer
    response = await client.get("/api/devices/999/live.mp4")
    assert response.status == 404


async def test_non_camera_cannot_open_a_stream(viewer):
    client, _ = viewer
    response = await client.get("/api/devices/2/live.mp4")
    assert response.status == 400


async def test_unconfirmed_stream_reports_unavailable(viewer):
    client, camera = viewer
    camera.state.pop("streamAvailable")
    response = await client.get("/api/devices/10000001/live.mp4")
    assert response.status == 503
    assert "error" in await response.json()


async def test_client_cannot_choose_upstream_or_change_stream_id(viewer, monkeypatch):
    client, _ = viewer
    queries = []

    async def media(request):
        queries.append(dict(request.query))
        return web.Response(body=b"sample-mp4-bytes", content_type="video/mp4")

    server = await upstream(media, monkeypatch)
    try:
        response = await client.get(
            "/api/devices/10000001/live.mp4?src=exec:bad&url=http://invalid/&foo=bar"
        )
        assert response.status == 200
        assert await response.read() == b"sample-mp4-bytes"
        assert response.headers["Content-Type"].startswith("video/mp4")
        assert response.headers["Cache-Control"] == "no-store"
        assert queries == [{"src": "10000001"}]
        assert (await client.put("/api/devices/10000001/live.mp4")).status == 405
    finally:
        await server.close()


async def test_upstream_failure_is_not_a_video_response(viewer, monkeypatch):
    client, _ = viewer

    async def missing(request):
        return web.json_response({"error": "no producer"}, status=404)

    server = await upstream(missing, monkeypatch)
    try:
        response = await client.get("/api/devices/10000001/live.mp4")
        assert response.status == 503
        assert "error" in await response.json()
    finally:
        await server.close()


async def test_stream_forwards_first_chunk_before_upstream_finishes(viewer, monkeypatch):
    client, _ = viewer
    release = asyncio.Event()

    async def media(request):
        response = web.StreamResponse(headers={"Content-Type": "video/mp4"})
        await response.prepare(request)
        await response.write(b"first-fragment")
        await release.wait()
        await response.write(b"last-fragment")
        return response

    server = await upstream(media, monkeypatch)
    try:
        response = await client.get("/api/devices/10000001/live.mp4")
        assert response.status == 200
        first = await asyncio.wait_for(response.content.readexactly(14), timeout=2)
        assert first == b"first-fragment"
        release.set()
        assert await response.read() == b"last-fragment"
    finally:
        release.set()
        await server.close()


async def test_closing_viewer_releases_upstream(viewer, monkeypatch):
    client, _ = viewer
    disconnected = asyncio.Event()

    async def media(request):
        response = web.StreamResponse(headers={"Content-Type": "video/mp4"})
        await response.prepare(request)
        try:
            while True:
                await response.write(b"frame" * 128)
                await asyncio.sleep(0.02)
        except (ConnectionResetError, asyncio.CancelledError):
            disconnected.set()
        return response

    server = await upstream(media, monkeypatch)
    try:
        response = await client.get("/api/devices/10000001/live.mp4")
        assert response.status == 200
        assert await response.content.read(512)
        response.close()
        await asyncio.wait_for(disconnected.wait(), timeout=3)
    finally:
        await server.close()


async def test_mse_relays_fixed_device_media_and_codec_negotiation(viewer, monkeypatch):
    client, _ = viewer
    received = []

    async def media(request):
        received.append(dict(request.query))
        socket = web.WebSocketResponse()
        await socket.prepare(request)
        received.append(await socket.receive_json())
        await socket.send_json({"type": "mse", "value": 'video/mp4; codecs="avc1.640029"'})
        await socket.send_bytes(b"mp4-fragment")
        async for _ in socket:
            pass
        return socket

    server = await upstream(media, monkeypatch, "/api/ws")
    try:
        socket = await client.ws_connect("/api/devices/10000001/live.ws?src=other")
        await socket.send_json({"type": "mse", "value": "avc1.640029,mp4a.40.2"})
        assert await socket.receive_json() == {
            "type": "mse", "value": 'video/mp4; codecs="avc1.640029"'
        }
        message = await socket.receive()
        assert message.type == WSMsgType.BINARY
        assert message.data == b"mp4-fragment"
        assert received == [{"src": "10000001"},
                            {"type": "mse", "value": "avc1.640029,mp4a.40.2"}]
        await socket.close()
    finally:
        await server.close()


async def test_mse_rejects_non_viewing_messages(viewer, monkeypatch):
    client, _ = viewer
    received = []
    ended = asyncio.Event()

    async def media(request):
        socket = web.WebSocketResponse()
        await socket.prepare(request)
        async for message in socket:
            received.append(message.data)
        ended.set()
        return socket

    server = await upstream(media, monkeypatch, "/api/ws")
    try:
        socket = await client.ws_connect("/api/devices/10000001/live.ws")
        await socket.send_json({"type": "streams", "value": "exec:bad"})
        assert (await socket.receive()).type in (WSMsgType.CLOSE, WSMsgType.CLOSED)
        await asyncio.wait_for(ended.wait(), timeout=2)
        assert received == []
    finally:
        await server.close()


async def test_closing_mse_viewer_closes_even_a_quiet_upstream(viewer, monkeypatch):
    client, _ = viewer
    ended = asyncio.Event()

    async def media(request):
        socket = web.WebSocketResponse()
        await socket.prepare(request)
        async for _ in socket:
            pass
        ended.set()
        return socket

    server = await upstream(media, monkeypatch, "/api/ws")
    try:
        socket = await client.ws_connect("/api/devices/10000001/live.ws")
        await socket.close()
        await asyncio.wait_for(ended.wait(), timeout=2)
    finally:
        await server.close()


async def test_dead_mse_upstream_fails_before_browser_upgrade(viewer, monkeypatch):
    client, _ = viewer
    async def idle(request):
        return web.Response()
    server = await upstream(idle, monkeypatch, "/api/ws")
    await server.close()
    with pytest.raises(WSServerHandshakeError) as error:
        await client.ws_connect("/api/devices/10000001/live.ws")
    assert error.value.status == 503


async def test_cross_origin_page_cannot_open_camera_websocket(viewer, monkeypatch):
    client, _ = viewer
    received = []

    async def media(request):
        received.append(True)
        socket = web.WebSocketResponse()
        await socket.prepare(request)
        async for _ in socket:
            pass
        return socket

    server = await upstream(media, monkeypatch, "/api/ws")
    try:
        with pytest.raises(WSServerHandshakeError) as error:
            async with client.ws_connect("/api/devices/10000001/live.ws", origin="https://other.example"):
                pass
        assert error.value.status == 403
        assert received == []
    finally:
        await server.close()


async def test_mse_allows_only_one_negotiation_per_connection(viewer, monkeypatch):
    client, _ = viewer
    received = []
    first = asyncio.Event()

    async def media(request):
        socket = web.WebSocketResponse()
        await socket.prepare(request)
        async for message in socket:
            received.append(message.json())
            first.set()
        return socket

    server = await upstream(media, monkeypatch, "/api/ws")
    socket = None
    try:
        socket = await client.ws_connect("/api/devices/10000001/live.ws")
        await socket.send_json({"type": "mse", "value": "avc1.640029"})
        await asyncio.wait_for(first.wait(), timeout=2)
        await socket.send_json({"type": "mse", "value": "avc1.640029"})
        message = await asyncio.wait_for(socket.receive(), timeout=2)
        assert message.type in (WSMsgType.CLOSE, WSMsgType.CLOSED)
        assert received == [{"type": "mse", "value": "avc1.640029"}]
    finally:
        if socket:
            await socket.close()
        await server.close()


async def test_non_cooperating_upstream_is_released_within_two_seconds(viewer, monkeypatch):
    client, _ = viewer
    disconnected = asyncio.Event()

    async def media(request):
        socket = web.WebSocketResponse()
        await socket.prepare(request)
        try:
            # This peer writes frames but never reads a WebSocket CLOSE.
            while True:
                await socket.send_bytes(b"frame")
                await asyncio.sleep(0.02)
        except (ConnectionResetError, asyncio.CancelledError):
            disconnected.set()
        return socket

    server = await upstream(media, monkeypatch, "/api/ws")
    try:
        socket = await client.ws_connect("/api/devices/10000001/live.ws")
        assert (await socket.receive()).data == b"frame"
        await socket.close()
        await asyncio.wait_for(disconnected.wait(), timeout=2)
    finally:
        await server.close()
