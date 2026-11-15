"""
One voice scan on the recording line (F-PR-13, ADR 0011), without LiveKit: read the code, claim the scan session,
wait for the merge, beep for both parties, listen to a few seconds of the other party, score them with the
voice-spoof service, look for scam cues in what was said and report only the outcome (a probability and cue ids).
The audio and the transcript stay in memory and are dropped. The LiveKit wiring is in `scanner.py`.
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass, field
from typing import Protocol

from . import tones
from .codes import CodeReader
from .scam_cues import find_cues
from .server_api import SCAN, Claim, OtherKind, ServerApi, ServerError
from .session import Player, _until_ended
from .settings import ScannerSettings
from .speech import SpeechWindows

log = logging.getLogger("opencaller.scanner")

# Speech-to-text of the sampled audio (16 kHz mono 16-bit PCM) for the scam cues; runs in a worker thread.
Transcribe = Callable[[bytes], str]


class SpoofScorer(Protocol):
    """ml/voice-spoof's `/v1/score`: the spoof probability of one 4 s window."""

    async def score(self, pcm: bytes) -> float: ...


@dataclass
class ScanOutcome:
    claimed: bool = False
    handed_over: bool = False
    reported: bool = False
    probability: float | None = None
    cues: list[str] = field(default_factory=list)


class ScanCall:
    """The scanner's work for one call; [run] returns when the outcome is reported (or nothing was claimed)."""

    def __init__(
        self,
        settings: ScannerSettings,
        server: ServerApi,
        player: Player,
        codes: CodeReader,
        scorer: SpoofScorer,
        transcribe: Transcribe | None,
        *,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self._settings = settings
        self._server = server
        self._player = player
        self._codes = codes
        self._scorer = scorer
        self._transcribe = transcribe
        self._sleep = sleep

    async def run(self, room: str, audio: Callable[[], AsyncIterator[bytes]], ended: asyncio.Event) -> ScanOutcome:
        """[audio] opens the other party's audio (16 kHz mono PCM chunks) once the scanner starts listening."""
        outcome = ScanOutcome()
        # The recorder plays the ready signal and the rejection; the scanner stays silent until it has the call.
        claim = await self._claim(room, ended)
        if isinstance(claim, OtherKind):
            outcome.handed_over = True
            return outcome
        if claim is None:
            return outcome
        outcome.claimed = True
        try:
            windows = await self._listen(claim, audio, ended)
            if windows:
                scores = [await self._scorer.score(window) for window in windows]
                outcome.probability = sum(scores) / len(scores)
                outcome.cues = await self._cues(b"".join(windows))
        except (TimeoutError, OSError, ServerError, ValueError) as e:
            log.warning("Scan failed: %s", type(e).__name__)
            outcome.probability = None
            outcome.cues = []
        if outcome.probability is None:
            log.info("Scan session %s: no result", claim.session_id)
        try:
            await self._server.report_scan(claim.session_id, probability=outcome.probability, cues=outcome.cues)
            outcome.reported = True
        except (TimeoutError, OSError, ServerError) as e:  # the session expires as failed on the server
            log.warning("Couldn't report the scan: %s", e)
        return outcome

    async def _claim(self, room: str, ended: asyncio.Event) -> Claim | OtherKind | None:
        for _ in range(self._settings.max_code_attempts):
            code = await _until_ended(self._codes.read(self._settings.code_timeout_s), ended)
            if code is None:
                return None
            claim = await self._server.claim(code, room, kind=SCAN)
            if claim is not None:
                return claim
        return None

    async def _listen(
        self, claim: Claim, audio: Callable[[], AsyncIterator[bytes]], ended: asyncio.Event
    ) -> list[bytes]:
        """Up to [ScannerSettings.windows] windows of speech, heard after the merge and the beep."""
        await self._sleep(self._settings.merge_delay_s)
        if ended.is_set():
            return []
        await self._player.play(tones.scan_beep())
        limit = min(self._settings.max_listen_s, claim.max_minutes * 60.0)
        collected: list[bytes] = []
        splitter = SpeechWindows()

        async def collect() -> None:
            stream = audio()
            try:
                async for pcm in stream:
                    collected.extend(splitter.feed(pcm))
                    if len(collected) >= self._settings.windows:
                        return
            finally:
                with contextlib.suppress(Exception):
                    await stream.aclose()  # type: ignore[attr-defined]

        task = asyncio.ensure_future(collect())
        stop = asyncio.ensure_future(ended.wait())
        try:
            await asyncio.wait({task, stop}, timeout=limit, return_when=asyncio.FIRST_COMPLETED)
        finally:
            for pending in (task, stop):
                pending.cancel()
            await asyncio.gather(task, stop, return_exceptions=True)
        if splitter.dropped:
            log.info("Dropped %d quiet windows", splitter.dropped)
        return collected[: self._settings.windows]

    async def _cues(self, pcm: bytes) -> list[str]:
        if self._transcribe is None:
            return []
        try:
            text = await asyncio.to_thread(self._transcribe, pcm)
        except Exception as e:  # noqa: BLE001 - the voice result stands without cues
            log.warning("Transcription for the cues failed: %s", type(e).__name__)
            return []
        return find_cues(text)
