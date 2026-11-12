"""
The assistant's messages to and from the user's app (ADR 0010): JSON over the LiveKit data channel, topic
`opencaller.assistant`, version 1. The agent sends only to the user's app participant, never to the caller's SIP
participant; it accepts messages only from that app participant.

Agent → app: `hello` (the caller's number, or hidden), `partial` (what the caller is saying now), `line` (a finished
line of the caller or the assistant), `details` (name, reason, urgency, likely spam), `ended` (why).
App → agent: `reply` (text the assistant says to the caller), `callback` (the user will call back; the assistant says
so and hangs up), `spam` (the assistant ends the call).
"""

from __future__ import annotations

import json
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Any

TOPIC = "opencaller.assistant"
VERSION = 1
MAX_REPLY_CHARS = 200
MAX_MESSAGE_BYTES = 4096
URGENCIES = ("low", "normal", "high")


@dataclass(frozen=True)
class Details:
    """What the assistant learned from the caller (filled by the language model's tool call)."""

    name: str | None = None
    reason: str | None = None
    urgency: str = "normal"
    likely_spam: bool = False

    @classmethod
    def of(cls, name: str | None, reason: str | None, urgency: str | None, likely_spam: bool | None) -> Details:
        """Cleans what the model gave: short strings, a known urgency."""
        return cls(
            name=_short(name, 80),
            reason=_short(reason, 300),
            urgency=urgency if urgency in URGENCIES else "normal",
            likely_spam=bool(likely_spam),
        )

    def message(self) -> dict[str, Any]:
        return {
            "t": "details",
            "name": self.name,
            "reason": self.reason,
            "urgency": self.urgency,
            "likelySpam": self.likely_spam,
        }


@dataclass(frozen=True)
class Reply:
    """The user typed [text] for the assistant to say."""

    text: str


@dataclass(frozen=True)
class CallBack:
    """The user will call back."""


@dataclass(frozen=True)
class Spam:
    """The user marked the call as spam."""


AppAction = Reply | CallBack | Spam


def parse_app_message(payload: bytes) -> AppAction | None:
    """The app's action, or None for anything unreadable or unknown (ignored, never fatal)."""
    if len(payload) > MAX_MESSAGE_BYTES:
        return None
    try:
        message = json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return None
    if not isinstance(message, dict):
        return None
    kind = message.get("t")
    if kind == "reply":
        text = _short(message.get("text"), MAX_REPLY_CHARS)
        return Reply(text) if text else None
    if kind == "callback":
        return CallBack()
    if kind == "spam":
        return Spam()
    return None


class AppChannel:
    """
    Sends the agent's messages to the user's app. Every finished line is kept for the call, so an app that joins
    late (the push arrived after the greeting) gets the whole conversation: [joined] sends it again.
    """

    def __init__(self, send: Callable[[bytes, list[str]], Awaitable[None]]) -> None:
        self._send = send
        self._identity: str | None = None
        self._hello: dict[str, Any] | None = None
        self._lines: list[dict[str, Any]] = []
        self._details: dict[str, Any] | None = None

    @property
    def app_identity(self) -> str | None:
        return self._identity

    def accepts(self, sender_identity: str | None) -> bool:
        """`True` for messages from the user's app participant only."""
        return self._identity is not None and sender_identity == self._identity

    async def start(self, app_identity: str, caller: str | None) -> None:
        self._identity = app_identity
        self._hello = {"t": "hello", "v": VERSION, "caller": caller, "callerHidden": caller is None}
        await self._emit(self._hello)

    async def partial(self, text: str) -> None:
        await self._emit({"t": "partial", "text": _short(text, 500)})

    async def line(self, role: str, text: str) -> None:
        message = {"t": "line", "role": role, "text": _short(text, 1000)}
        if message["text"]:
            self._lines.append(message)
            await self._emit(message)

    async def details(self, details: Details) -> None:
        self._details = details.message()
        await self._emit(self._details)

    async def ended(self, reason: str) -> None:
        await self._emit({"t": "ended", "reason": reason})

    async def joined(self, identity: str) -> None:
        """The app's participant joined the room: everything so far, in order."""
        if identity != self._identity or self._hello is None:
            return
        for message in [self._hello, *self._lines, *([self._details] if self._details else [])]:
            await self._emit(message)

    @property
    def transcript(self) -> list[dict[str, Any]]:
        return list(self._lines)

    async def _emit(self, message: dict[str, Any]) -> None:
        if self._identity is None:
            return
        await self._send(json.dumps(message, ensure_ascii=False).encode("utf-8"), [self._identity])


def _short(value: Any, limit: int) -> str | None:
    if not isinstance(value, str):
        return None
    text = " ".join(value.split())
    return text[:limit] or None
