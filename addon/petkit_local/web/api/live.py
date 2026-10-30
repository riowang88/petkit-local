"""Viewer-only access to registered cameras through the local go2rtc sidecar."""
from __future__ import annotations

import asyncio
import json
from urllib.parse import urlsplit

import aiohttp
from aiohttp import web

from petkit_local.media import go2rtc
from petkit_local.web.api._common import _device_or_404


def _live_device(request: web.Request):
    device = _device_or_404(request)
    if not device.is_camera:
        raise web.HTTPBadRequest(text=json.dumps({"error": "该设备没有摄像头"}),
                                 content_type="application/json")
    if not device.state.get(go2rtc.STREAM_AVAILABLE):
        raise web.HTTPServiceUnavailable(text=json.dumps({"error": "设备实时视频尚未就绪"}),
                                         content_type="application/json")
    return device


async def api_live_mp4(request: web.Request) -> web.StreamResponse:
    """Relay one fixed camera stream; client queries never reach go2rtc."""
    device = _live_device(request)

    timeout = aiohttp.ClientTimeout(total=None, sock_connect=5, sock_read=15)
    response = None
    try:
        async with aiohttp.ClientSession(timeout=timeout) as session:
            async with session.get(
                f"http://{go2rtc.API_ADDR}/api/stream.mp4",
                params={"src": str(device.petkit_id)},
                allow_redirects=False,
            ) as upstream:
                if upstream.status != 200 or upstream.content_type != "video/mp4":
                    return web.json_response({"error": "视频服务暂不可用，请稍后重试"}, status=503)
                response = web.StreamResponse(headers={
                    "Content-Type": "video/mp4",
                    "Cache-Control": "no-store",
                    "X-Accel-Buffering": "no",
                })
                await response.prepare(request)
                async for chunk in upstream.content.iter_chunked(65536):
                    await response.write(chunk)
                await response.write_eof()
                return response
    except (ConnectionResetError, BrokenPipeError, aiohttp.ClientError, asyncio.TimeoutError):
        # Exiting the upstream context closes its socket on viewer disconnect.
        # Headers already sent cannot be replaced by a JSON error mid-video.
        if response is not None:
            return response
        return web.json_response({"error": "视频服务连接失败，请稍后重试"}, status=503)


async def api_live_ws(request: web.Request) -> web.StreamResponse:
    """Bridge MSE media only; no stream configuration or other go2rtc API."""
    origin = request.headers.get("Origin")
    if origin:
        try:
            source = urlsplit(origin)
            allowed = source.scheme in ("http", "https") and source.netloc.lower() == request.host.lower()
        except ValueError:
            allowed = False
        if not allowed:
            return web.json_response({"error": "请从本后台页面打开实时画面"}, status=403)
    device = _live_device(request)
    browser = web.WebSocketResponse(heartbeat=20, max_msg_size=4096)
    tasks = []
    timeout = aiohttp.ClientTimeout(total=None, sock_connect=5)
    try:
        async with aiohttp.ClientSession(timeout=timeout) as session:
            upstream = await asyncio.wait_for(session.ws_connect(
                f"http://{go2rtc.API_ADDR}/api/ws",
                params={"src": str(device.petkit_id)},
                max_msg_size=4 * 1024 * 1024,
                heartbeat=20,
            ), timeout=5)
            await browser.prepare(request)

            async def from_browser():
                negotiated = False
                async for message in browser:
                    if negotiated or message.type != aiohttp.WSMsgType.TEXT:
                        await browser.close(code=1008, message=b"One MSE negotiation only")
                        return
                    try:
                        body = json.loads(message.data)
                    except (ValueError, TypeError):
                        body = None
                    if (not isinstance(body, dict) or set(body) != {"type", "value"}
                            or body.get("type") != "mse"
                            or not isinstance(body.get("value"), str)
                            or not 0 < len(body["value"]) <= 1024):
                        await browser.close(code=1008, message=b"MSE negotiation only")
                        return
                    negotiated = True
                    await upstream.send_json(body)

            async def from_upstream():
                async for message in upstream:
                    if message.type == aiohttp.WSMsgType.TEXT:
                        await browser.send_str(message.data)
                    elif message.type == aiohttp.WSMsgType.BINARY:
                        await browser.send_bytes(message.data)
                    else:
                        return

            tasks = [asyncio.create_task(from_browser()), asyncio.create_task(from_upstream())]
            try:
                await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            finally:
                # Stop readers before the close handshake; even an upstream
                # that never reads CLOSE must not retain the camera viewer.
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
                tasks.clear()
                try:
                    await asyncio.wait_for(upstream.close(), timeout=1)
                except (asyncio.TimeoutError, aiohttp.ClientError, ConnectionResetError):
                    pass
    except (aiohttp.ClientError, asyncio.TimeoutError, ConnectionResetError):
        if not browser.prepared:
            return web.json_response({"error": "视频服务连接失败，请稍后重试"}, status=503)
    finally:
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
        if browser.prepared:
            await browser.close()
    return browser
