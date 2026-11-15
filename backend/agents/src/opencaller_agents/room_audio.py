"""Plays the agents' sounds into the call's LiveKit room."""

from __future__ import annotations

from livekit import rtc

from . import tones


class RoomPlayer:
    """Plays PCM into a published audio track that everyone in the room (the merged call) hears."""

    def __init__(self, source: rtc.AudioSource) -> None:
        self._source = source

    async def play(self, pcm: bytes) -> None:
        for frame in tones.frames(pcm):
            await self._source.capture_frame(rtc.AudioFrame(frame, tones.SAMPLE_RATE, 1, tones.SAMPLES_PER_FRAME))
        await self._source.wait_for_playout()
