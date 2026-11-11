"""The OpenCaller server's agent endpoints (`/agent/v1/...`, bearer RECORDING_AGENT_TOKEN; openapi.yaml)."""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any, Protocol

import aiohttp


class Transport(Protocol):
    """Sends one JSON request; returns the status and the parsed JSON body (or None)."""

    async def send(self, method: str, path: str, body: dict[str, Any] | None) -> tuple[int, Any]: ...


class ServerError(RuntimeError):
    """The server answered something the agent can't use."""


@dataclass(frozen=True)
class Claim:
    """A claimed session: Egress must write [object_key]; recording stops after [max_minutes]."""

    session_id: str
    object_key: str
    max_minutes: int


@dataclass(frozen=True)
class TranscriptJob:
    recording_id: str
    url: str


class ServerApi:
    """What the agents ask the server. Never sends a phone number: the code identifies the session."""

    def __init__(self, transport: Transport) -> None:
        self._transport = transport

    async def claim(self, code: str, room: str) -> Claim | None:
        """The session waiting for [code], or None if no session waits for it (404) or the call tried too often (429)."""
        status, body = await self._transport.send("POST", "/agent/v1/recording/claim", {"code": code, "room": room})
        if status in (404, 429):
            return None
        _expect(status, 200, body)
        return Claim(str(body["sessionId"]), str(body["objectKey"]), int(body["maxMinutes"]))

    async def report(self, session_id: str, *, complete: bool, size_bytes: int = 0, duration_ms: int = 0) -> None:
        """The session's result; the server checks the file itself."""
        status, body = await self._transport.send(
            "POST",
            f"/agent/v1/recording/sessions/{session_id}/result",
            {"status": "complete" if complete else "failed", "sizeBytes": size_bytes, "durationMs": duration_ms},
        )
        _expect(status, 204, body)

    async def next_transcript(self) -> TranscriptJob | None:
        status, body = await self._transport.send("POST", "/agent/v1/transcripts/next", {})
        if status == 204:
            return None
        _expect(status, 200, body)
        return TranscriptJob(str(body["recordingId"]), str(body["url"]))

    async def put_transcript(self, recording_id: str, text: str | None) -> None:
        """[text], or None when transcribing failed."""
        payload: dict[str, Any] = {"text": text} if text is not None else {"failed": True}
        status, body = await self._transport.send("PUT", f"/agent/v1/transcripts/{recording_id}", payload)
        _expect(status, 204, body)


def _expect(status: int, wanted: int, body: Any) -> None:
    if status != wanted:
        code = body.get("code") if isinstance(body, dict) else None
        raise ServerError(f"HTTP {status} ({code or 'no problem code'})")


class AiohttpTransport:
    """[Transport] over aiohttp with the agent token."""

    def __init__(self, session: aiohttp.ClientSession, base_url: str, token: str) -> None:
        self._session = session
        self._base_url = base_url.rstrip("/")
        self._headers = {"Authorization": f"Bearer {token}"}

    async def send(self, method: str, path: str, body: dict[str, Any] | None) -> tuple[int, Any]:
        timeout = aiohttp.ClientTimeout(total=15)
        async with self._session.request(
            method, self._base_url + path, json=body, headers=self._headers, timeout=timeout
        ) as response:
            text = await response.text()
            return response.status, (json.loads(text) if text.strip() else None)
