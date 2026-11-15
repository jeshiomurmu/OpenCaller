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


RECORD = "record"
SCAN = "scan"


@dataclass(frozen=True)
class Claim:
    """A claimed session: Egress must write [object_key] (None for a scan); the agent stops after [max_minutes]."""

    session_id: str
    object_key: str | None
    max_minutes: int


@dataclass(frozen=True)
class OtherKind:
    """The code is the other line agent's session (a scan for the recorder or the reverse): leave the call to it."""


@dataclass(frozen=True)
class AssistantClaim:
    """A call the assistant may answer: data messages go to [app_identity] only; it ends after [remaining_seconds]."""

    call_id: str
    app_identity: str
    remaining_seconds: int


@dataclass(frozen=True)
class AssistantRefusal:
    """Why the assistant can't take a call: `number_not_found`, `assistant_limit`, `supporter_required`, ..."""

    code: str


@dataclass(frozen=True)
class TranscriptJob:
    recording_id: str
    url: str


class ServerApi:
    """What the agents ask the server. Never sends a phone number: the code identifies the session."""

    def __init__(self, transport: Transport) -> None:
        self._transport = transport

    async def claim(self, code: str, room: str, kind: str = RECORD) -> Claim | OtherKind | None:
        """
        The session of [kind] waiting for [code]; [OtherKind] when the code belongs to the other agent (409), None if no
        session waits for it (404) or the call tried too often (429).
        """
        request = {"code": code, "room": room, "kind": kind}
        status, body = await self._transport.send("POST", "/agent/v1/recording/claim", request)
        if status in (404, 429):
            return None
        if status == 409 and isinstance(body, dict) and body.get("code") == "other_kind":
            return OtherKind()
        _expect(status, 200, body)
        key = body.get("objectKey")
        return Claim(str(body["sessionId"]), None if key is None else str(key), int(body["maxMinutes"]))

    async def report(self, session_id: str, *, complete: bool, size_bytes: int = 0, duration_ms: int = 0) -> None:
        """The session's result; the server checks the file itself."""
        status, body = await self._transport.send(
            "POST",
            f"/agent/v1/recording/sessions/{session_id}/result",
            {"status": "complete" if complete else "failed", "sizeBytes": size_bytes, "durationMs": duration_ms},
        )
        _expect(status, 204, body)

    async def report_scan(self, session_id: str, *, probability: float | None, cues: list[str]) -> None:
        """A voice scan's outcome: the spoof probability and the scam-cue ids, or failed when [probability] is None."""
        payload: dict[str, Any] = (
            {"status": "failed"}
            if probability is None
            else {"status": "complete", "spoofProbability": round(min(1.0, max(0.0, probability)), 4), "cues": cues}
        )
        status, body = await self._transport.send("POST", f"/agent/v1/scanner/sessions/{session_id}/result", payload)
        _expect(status, 204, body)

    async def claim_assistant_call(self, number: str, room: str) -> AssistantClaim | AssistantRefusal:
        """The user who has the dialed [number]; the server pushes the call's id to their phone. Never the caller's number."""
        status, body = await self._transport.send("POST", "/agent/v1/assistant/calls", {"number": number, "room": room})
        if status == 200:
            return AssistantClaim(str(body["callId"]), str(body["appIdentity"]), int(body["remainingSeconds"]))
        if 400 <= status < 500:
            code = body.get("code") if isinstance(body, dict) else None
            return AssistantRefusal(str(code or f"http_{status}"))
        raise ServerError(f"HTTP {status}")

    async def end_assistant_call(self, call_id: str, duration_seconds: int) -> None:
        """The call's length, for the user's monthly minutes."""
        status, body = await self._transport.send(
            "POST", f"/agent/v1/assistant/calls/{call_id}/end", {"durationSeconds": max(0, duration_seconds)}
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
