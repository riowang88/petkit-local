"""Keeping secrets a device puts in a query string out of the logs.

A T6 polls `dev_package_auth?sn=...&secret=...` every minute, and aiohttp's
access log writes the whole request line verbatim, so the consumable secret
reached the process log hundreds of times a day. The filter sits on the root
HANDLERS rather than on one logger, so every logger that propagates there —
the device API's, the panel's and the bucket's access logs alike — is covered
without replacing aiohttp's access logger class.

Matching is by whole parameter name, case-insensitively. `sn`, `mac` and the
heartbeat counters stay readable: they are what a log is for.

Capture files are NOT redacted here or anywhere — see `utils/capture.py`.
"""
from __future__ import annotations

import logging
import re
from typing import Any

REDACTED = "<redacted>"

#: Query parameter names whose values never belong in a log.
SECRET_PARAMS = frozenset({"secret", "sign", "token", "password", "pwd", "key"})

_QUERY_SECRET = re.compile(
    r"([?&](?:" + "|".join(sorted(SECRET_PARAMS)) + r")=)[^&\s\"']*",
    re.IGNORECASE,
)


def redact_text(text: str) -> str:
    """`text` with the value of every secret query parameter replaced."""
    return _QUERY_SECRET.sub(r"\1" + REDACTED, text)


def redact_query(query: dict[str, Any]) -> dict[str, Any]:
    """A copy of a parsed query with secret values replaced."""
    return {k: (REDACTED if k.lower() in SECRET_PARAMS else v) for k, v in query.items()}


class RedactSecretsFilter(logging.Filter):
    """Rewrites a record's message so no secret query value survives it.

    Formats `msg % args` itself and drops the args, because the secret can
    arrive in either and only the rendered message is certain to show it.
    Never drops a record and never raises: a log line that cannot be rendered
    is left for the handler to report as it always would.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        try:
            message = record.getMessage()
        except Exception:
            return True
        redacted = redact_text(message)
        if redacted != message:
            record.msg = redacted
            record.args = None
        return True


def install_log_redaction(logger: logging.Logger | None = None) -> None:
    """Put one `RedactSecretsFilter` on each handler of `logger` (root by
    default). Idempotent, so calling it twice does not stack filters."""
    target = logger if logger is not None else logging.getLogger()
    for handler in target.handlers:
        if not any(isinstance(f, RedactSecretsFilter) for f in handler.filters):
            handler.addFilter(RedactSecretsFilter())
