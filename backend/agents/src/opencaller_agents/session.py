"""One call to the recording line, from the code to the reported result (the LiveKit wiring is in recorder.py)."""

from __future__ import annotations

import asyncio
import contextlib
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Protocol

from . import tones
from .codes import CodeReader
from .egress import EgressApi, finish, track_egress_request
from .server_api import Claim, ServerApi
from .settings import RecorderSettings

log = logging.getLogger("opencaller.recorder")


class Player(Protocol):
    """Plays PCM (48 kHz mono 16-bit) to everyone in the room, waiting until it has been played."""

    async def play(self, pcm: bytes) -> None: ...


@dataclass
class CallOutcome:
    """What happened (for the log and the tests)."""

    claimed: bool = False
    recorded: bool = False
    reported: bool = False


class RecordingCall:
    """
    The recorder's work for one call (ADR 0009): signal readiness, read the code (at most
    [RecorderSettings.max_code_attempts] tries), claim the session, start the track egress, play the beep and the
    announcement once the app has merged, beep while recording, then stop the egress when the call ends (or at the
    session's time limit) and report the result. It never learns or logs a phone number.
    """

    def __init__(
        self,
        settings: RecorderSettings,
        server: ServerApi,
        egress: EgressApi,
        player: Player,
        codes: CodeReader,
        *,
        announcement: bytes | None = None,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self._settings = settings
        self._server = server
        self._egress = egress
        self._player = player
        self._codes = codes
        self._announcement = announcement
        self._sleep = sleep

    async def run(self, room: str, caller_track: Callable[[], Awaitable[str]], ended: asyncio.Event) -> CallOutcome:
        """Runs until the call ends; [caller_track] gives the SIP participant's audio track id."""
        outcome = CallOutcome()
        await self._player.play(tones.ready_signal())
        claim = await self._claim(room, ended)
        if claim is None:
            if not ended.is_set():
                await self._player.play(tones.rejected_signal())
            return outcome
        outcome.claimed = True
        try:
            track_id = await caller_track()
            info = await self._egress.start_track_egress(
                track_egress_request(room, track_id, claim.object_key, self._settings.s3)
            )
        except Exception as e:  # noqa: BLE001 - the session must be reported failed whatever went wrong
            log.error("Could not start recording: %s", type(e).__name__)
            await self._server.report(claim.session_id, complete=False)
            outcome.reported = True
            return outcome
        log.info("Recording session %s (egress %s)", claim.session_id, info.egress_id)
        await self._record(claim, ended)
        result = await finish(self._egress, info.egress_id)
        await self._server.report(
            claim.session_id, complete=result.complete, size_bytes=result.size_bytes, duration_ms=result.duration_ms
        )
        outcome.recorded = result.complete
        outcome.reported = True
        return outcome

    async def _claim(self, room: str, ended: asyncio.Event) -> Claim | None:
        for _ in range(self._settings.max_code_attempts):
            code = await _until_ended(self._codes.read(self._settings.code_timeout_s), ended)
            if code is None:
                return None
            claim = await self._server.claim(code, room)
            if claim is not None:
                return claim
        return None

    # The beep and announcement wait for the merge, then a short beep every interval until the call ends or the
    # session's limit is reached.
    async def _record(self, claim: Claim, ended: asyncio.Event) -> None:
        async def warnings() -> None:
            await self._sleep(self._settings.announce_delay_s)
            await self._player.play(tones.recording_beep())
            if self._announcement:
                await self._player.play(self._announcement)
            while self._settings.beep_interval_s > 0:
                await self._sleep(self._settings.beep_interval_s)
                await self._player.play(tones.reminder_beep())

        task = asyncio.create_task(warnings())
        try:
            with contextlib.suppress(TimeoutError):
                await asyncio.wait_for(ended.wait(), timeout=claim.max_minutes * 60)
        finally:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task


async def _until_ended(read: Awaitable[str | None], ended: asyncio.Event) -> str | None:
    """[read]'s code, or None if the call ends first."""
    reading = asyncio.ensure_future(read)
    waiting = asyncio.ensure_future(ended.wait())
    done, pending = await asyncio.wait({reading, waiting}, return_when=asyncio.FIRST_COMPLETED)
    for task in pending:
        task.cancel()
    await asyncio.gather(*pending, return_exceptions=True)
    return reading.result() if reading in done else None
