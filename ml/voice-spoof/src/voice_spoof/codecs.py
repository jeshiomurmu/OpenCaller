"""
Telephone-channel degradation for the evaluation, with ffmpeg: what a voice goes through before it reaches the
scanner agent. Every condition ends as 16 kHz mono float audio again.

- `clean`: the evaluation audio as it is (16 kHz).
- `g711`: 8 kHz G.711 µ-law, the PSTN and SIP trunk codec (Twilio Elastic SIP Trunking uses G.711).
- `gsm`: 8 kHz GSM-FR (06.10), the 2G mobile codec still common on older networks in South Asia.
- `g711_opus`: G.711 as above, then Opus at 24 kbit/s, the path in this stack: trunk → LiveKit SIP → WebRTC.
"""

from __future__ import annotations

import subprocess

import numpy as np

CONDITIONS = ("clean", "g711", "gsm", "g711_opus")

# ffmpeg pipelines: (encode args, container) per step. Input and output are raw float32 at 16 kHz. The decoder is
# told the container: raw GSM has no header, and probing it fails on some inputs.
_STEPS: dict[str, list[tuple[list[str], str]]] = {
    "clean": [],
    "g711": [(["-ar", "8000", "-ac", "1", "-c:a", "pcm_mulaw"], "wav")],
    "gsm": [(["-ar", "8000", "-ac", "1", "-c:a", "libgsm"], "gsm")],
    "g711_opus": [
        (["-ar", "8000", "-ac", "1", "-c:a", "pcm_mulaw"], "wav"),
        (["-ar", "48000", "-ac", "1", "-c:a", "libopus", "-b:a", "24k"], "ogg"),
    ],
}


def _ffmpeg(args: list[str], data: bytes) -> bytes:
    run = subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", *args], input=data, capture_output=True, check=False
    )
    if run.returncode != 0:
        raise RuntimeError(run.stderr.decode(errors="replace").strip() or "ffmpeg failed")
    return run.stdout


def degrade(audio: np.ndarray, condition: str, sample_rate: int = 16_000) -> np.ndarray:
    """[audio] (float32, mono, [sample_rate]) through [condition], back at 16 kHz."""
    if condition not in _STEPS:
        raise ValueError(f"unknown condition {condition}")
    data = audio.astype("<f4").tobytes()
    rate = sample_rate
    for encode, container in _STEPS[condition]:
        encoded = _ffmpeg(
            ["-f", "f32le", "-ar", str(rate), "-ac", "1", "-i", "pipe:0", *encode, "-f", container, "pipe:1"], data
        )
        data = _ffmpeg(["-f", container, "-i", "pipe:0", "-ar", "16000", "-ac", "1", "-f", "f32le", "pipe:1"], encoded)
        rate = 16_000
    if not _STEPS[condition] and sample_rate != 16_000:
        data = _ffmpeg(
            [
                "-f",
                "f32le",
                "-ar",
                str(sample_rate),
                "-ac",
                "1",
                "-i",
                "pipe:0",
                "-ar",
                "16000",
                "-f",
                "f32le",
                "pipe:1",
            ],
            data,
        )
    return np.frombuffer(data, dtype="<f4").copy()
