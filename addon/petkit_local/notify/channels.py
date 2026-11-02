"""The outbound notification APIs, and which addresses may be used for them.

Both channel addresses ARE their credential: anyone holding the URL can post as
the operator. They are also written from a panel that may be reachable with no
authentication, so a setting is only accepted on the service's own HTTPS host —
otherwise saving a URL would be a way to have every event sent somewhere else.

An error string here reaches the log and the panel, so it never contains the
URL: an aiohttp exception quotes it, which is why only the type name is kept.
"""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass
from typing import Any

import aiohttp

log = logging.getLogger(__name__)

SPUG_URL = re.compile(r"https://push\.spug\.cc/(send|xsend)/[A-Za-z0-9_-]+")
WECOM_URL = re.compile(r"https://qyapi\.weixin\.qq\.com/cgi-bin/webhook/send\?key=[A-Za-z0-9-]+")
#: `voice`, `sms`, `mail`, `wx_mp`, `wx`, `dd`, `fs`, joined with `|`.
SPUG_CHANNEL = re.compile(r"[a-z_]+(\|[a-z_]+)*")

#: WeCom's documented limit on a text message, in UTF-8 bytes.
WECOM_TEXT_LIMIT = 2048


class NotifyError(Exception):
    """A service answered and refused the message."""


@dataclass(frozen=True)
class Message:
    """One notification, already rendered for a person."""

    title: str
    body: str


def valid_spug_url(value: str) -> bool:
    """Whether `value` is empty or a push.spug.cc send/xsend address."""
    return not value or SPUG_URL.fullmatch(value) is not None


def valid_wecom_url(value: str) -> bool:
    """Whether `value` is empty or a WeCom group-robot address."""
    return not value or WECOM_URL.fullmatch(value) is not None


def valid_spug_channel(value: str) -> bool:
    """Whether `value` is empty or a `|`-joined list of Spug channel names."""
    return not value or SPUG_CHANNEL.fullmatch(value) is not None


def _truncate_utf8(text: str, limit: int) -> str:
    """`text` cut to `limit` bytes without splitting a character."""
    return text.encode("utf-8")[:limit].decode("utf-8", "ignore")


async def _post(session: aiohttp.ClientSession, url: str, body: dict[str, Any]) -> dict[str, Any]:
    """POST `body` and return the service's JSON object, `{}` if it sent none."""
    async with session.post(url, json=body) as resp:
        if resp.status >= 300:
            raise NotifyError(f"HTTP {resp.status}")
        try:
            answer = await resp.json(content_type=None)
        except ValueError:
            return {}
    return answer if isinstance(answer, dict) else {}


async def send_spug(session: aiohttp.ClientSession, url: str, channel: str,
                    message: Message) -> None:
    """Send through Spug's push assistant.

    `/xsend/<user>` takes the message directly. `/send/<template>` fills the
    template's variables, which the operator names, so the template has to use
    `${key1}` for the title and `${key2}` for the body.

    Raises:
        NotifyError: on an HTTP failure, or a JSON `code` other than 200.
    """
    if "/xsend/" in url:
        body: dict[str, Any] = {"title": message.title, "content": message.body}
        if channel:
            body["channel"] = channel
    else:
        body = {"key1": message.title, "key2": message.body}
    answer = await _post(session, url, body)
    code = answer.get("code", 200)
    if code not in (0, 200):
        raise NotifyError(f"code {code}: {answer.get('msg') or answer.get('message') or ''}")


async def send_wecom(session: aiohttp.ClientSession, url: str, message: Message) -> None:
    """Send a text message through a WeCom group robot.

    Text rather than markdown because it renders the same in every client.

    Raises:
        NotifyError: on an HTTP failure, or a non-zero `errcode`.
    """
    content = _truncate_utf8(f"{message.title}\n{message.body}", WECOM_TEXT_LIMIT)
    answer = await _post(session, url, {"msgtype": "text", "text": {"content": content}})
    errcode = answer.get("errcode", 0)
    if errcode != 0:
        raise NotifyError(f"errcode {errcode}: {answer.get('errmsg') or ''}")


async def deliver(session: aiohttp.ClientSession, live: dict[str, Any],
                  message: Message) -> dict[str, str]:
    """Send `message` on every configured channel.

    Returns:
        `{channel: error}` for each channel tried, "" meaning accepted. One
        failing channel never stops the other.
    """
    sends = {}
    if live.get("notify_spug_url"):
        sends["spug"] = send_spug(session, live["notify_spug_url"],
                                  live.get("notify_spug_channel", ""), message)
    if live.get("notify_wecom_url"):
        sends["wecom"] = send_wecom(session, live["notify_wecom_url"], message)

    results = {}
    for name, send in sends.items():
        try:
            await send
            results[name] = ""
        except NotifyError as e:
            results[name] = str(e)
        except (aiohttp.ClientError, OSError, TimeoutError) as e:
            results[name] = type(e).__name__
    return results
