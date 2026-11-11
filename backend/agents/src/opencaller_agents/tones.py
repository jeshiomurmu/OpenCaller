"""The sounds the recorder plays: 16-bit mono PCM at 48 kHz, cut into 10 ms frames for a LiveKit AudioSource."""

from __future__ import annotations

import array
import math
import sys
import wave
from collections.abc import Iterator

SAMPLE_RATE = 48_000
FRAME_MS = 10
SAMPLES_PER_FRAME = SAMPLE_RATE * FRAME_MS // 1000
_FADE_MS = 5
_MAX = 32_767


def tone(frequency_hz: float, duration_ms: int, volume: float = 0.3) -> bytes:
    """A sine tone with short fades (no clicks)."""
    count = SAMPLE_RATE * duration_ms // 1000
    fade = SAMPLE_RATE * _FADE_MS // 1000
    samples = array.array("h")
    for i in range(count):
        envelope = min(1.0, i / fade, (count - 1 - i) / fade) if fade else 1.0
        value = math.sin(2 * math.pi * frequency_hz * i / SAMPLE_RATE) * volume * envelope
        samples.append(round(value * _MAX))
    return _little_endian(samples)


def silence(duration_ms: int) -> bytes:
    return bytes(2 * (SAMPLE_RATE * duration_ms // 1000))


def recording_beep() -> bytes:
    """The warning both parties hear when recording starts and, shorter, while it runs."""
    return tone(1400, 400)


def reminder_beep() -> bytes:
    return tone(1400, 150, volume=0.2)


def ready_signal() -> bytes:
    """Two short tones: the line answered and waits for the code."""
    return tone(880, 150) + silence(100) + tone(880, 150)


def rejected_signal() -> bytes:
    """Three low tones: no session waits for this code; the line hangs up."""
    return (tone(440, 250) + silence(150)) * 3


def read_wav(path: str) -> bytes:
    """A 16-bit PCM WAV (mono or stereo, any rate) as mono 48 kHz PCM."""
    with wave.open(path, "rb") as wav:
        if wav.getsampwidth() != 2:
            raise ValueError("The announcement must be 16-bit PCM")
        channels = wav.getnchannels()
        rate = wav.getframerate()
        raw = wav.readframes(wav.getnframes())
    samples = array.array("h")
    samples.frombytes(raw)
    if sys.byteorder == "big":
        samples.byteswap()
    if channels > 1:
        samples = array.array(
            "h", (sum(samples[i : i + channels]) // channels for i in range(0, len(samples), channels))
        )
    return _little_endian(_resample(samples, rate))


def frames(pcm: bytes) -> Iterator[bytes]:
    """[pcm] in 10 ms frames; the last one is padded with silence."""
    size = SAMPLES_PER_FRAME * 2
    for start in range(0, len(pcm), size):
        chunk = pcm[start : start + size]
        yield chunk + bytes(size - len(chunk))


def _resample(samples: array.array, rate: int) -> array.array:
    if rate == SAMPLE_RATE or not samples:
        return samples
    count = len(samples) * SAMPLE_RATE // rate
    out = array.array("h")
    for i in range(count):
        position = i * rate / SAMPLE_RATE
        left = int(position)
        right = min(left + 1, len(samples) - 1)
        fraction = position - left
        out.append(round(samples[left] * (1 - fraction) + samples[right] * fraction))
    return out


def _little_endian(samples: array.array) -> bytes:
    if sys.byteorder == "big":
        samples = array.array("h", samples)
        samples.byteswap()
    return samples.tobytes()
