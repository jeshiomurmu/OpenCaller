"""
Cuts the other party's audio (16 kHz mono 16-bit PCM) into the 4-second windows the voice-spoof service scores.
The windows are taken as they come, not stitched from voiced pieces (the model was trained on natural recordings,
pauses included); a window that is mostly silence is dropped.
"""

from __future__ import annotations

import array
import math
import sys

SAMPLE_RATE = 16_000
WINDOW_SAMPLES = 4 * SAMPLE_RATE  # what ml/voice-spoof's /v1/score takes
_CHUNK = SAMPLE_RATE // 50  # 20 ms


def _samples(pcm: bytes) -> array.array:
    samples = array.array("h")
    samples.frombytes(pcm[: len(pcm) - len(pcm) % 2])
    if sys.byteorder == "big":
        samples.byteswap()
    return samples


def voiced_fraction(pcm: bytes, rms_threshold: float) -> float:
    """The share of 20 ms chunks whose loudness (RMS, 16-bit scale) reaches [rms_threshold]."""
    samples = _samples(pcm)
    chunks = len(samples) // _CHUNK
    if chunks == 0:
        return 0.0
    voiced = 0
    for i in range(chunks):
        chunk = samples[i * _CHUNK : (i + 1) * _CHUNK]
        if math.sqrt(sum(s * s for s in chunk) / _CHUNK) >= rms_threshold:
            voiced += 1
    return voiced / chunks


class SpeechWindows:
    """Collects PCM and hands out 4 s windows with at least [min_voiced] of them voiced."""

    def __init__(self, *, rms_threshold: float = 300.0, min_voiced: float = 0.4) -> None:
        self._buffer = bytearray()
        self._rms_threshold = rms_threshold
        self._min_voiced = min_voiced
        self.dropped = 0

    def feed(self, pcm: bytes) -> list[bytes]:
        """The windows completed by [pcm] (usually none or one)."""
        self._buffer += pcm
        size = WINDOW_SAMPLES * 2
        windows: list[bytes] = []
        while len(self._buffer) >= size:
            window = bytes(self._buffer[:size])
            del self._buffer[:size]
            if voiced_fraction(window, self._rms_threshold) >= self._min_voiced:
                windows.append(window)
            else:
                self.dropped += 1
        return windows
