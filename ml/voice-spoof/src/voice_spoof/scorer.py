"""Scores audio with the exported AASIST model (onnxruntime, CPU)."""

from __future__ import annotations

from pathlib import Path
from typing import Protocol

import numpy as np

SAMPLE_RATE = 16_000
# What the service accepts: exactly 4 s of 16 kHz mono audio.
INPUT_SAMPLES = 4 * SAMPLE_RATE
# What AASIST was trained on (config/AASIST.conf nb_samp): about 4.04 s.
MODEL_SAMPLES = 64_600


class Session(Protocol):
    def run(self, output_names: list[str], inputs: dict[str, np.ndarray]) -> list[np.ndarray]: ...


def fit(audio: np.ndarray, length: int = MODEL_SAMPLES) -> np.ndarray:
    """Cuts or repeats [audio] to [length] samples, as AASIST's own `pad` does."""
    if audio.size == 0:
        raise ValueError("empty audio")
    if audio.size >= length:
        return audio[:length]
    return np.tile(audio, length // audio.size + 1)[:length]


def spoof_probability(logits: np.ndarray) -> float:
    """Softmax over AASIST's (spoof, bona fide) logits: the share of the spoof class, not a calibrated probability."""
    shifted = logits - logits.max()
    weights = np.exp(shifted)
    return float(weights[0] / weights.sum())


class Scorer:
    """One model session, shared by the service's requests (onnxruntime sessions are thread-safe for `run`)."""

    def __init__(self, session: Session) -> None:
        self._session = session

    @classmethod
    def load(cls, model: Path, threads: int = 0) -> Scorer:
        import onnxruntime as ort

        options = ort.SessionOptions()
        if threads > 0:
            options.intra_op_num_threads = threads
        return cls(ort.InferenceSession(str(model), options, providers=["CPUExecutionProvider"]))

    def logits(self, audio: np.ndarray) -> np.ndarray:
        batch = fit(audio.astype(np.float32))[np.newaxis, :]
        return self._session.run(["logits"], {"audio": batch})[0][0]

    def score(self, audio: np.ndarray) -> float:
        """Spoof probability of float audio in [-1, 1] at 16 kHz."""
        return spoof_probability(self.logits(audio))


def pcm16_to_float(data: bytes) -> np.ndarray:
    """Little-endian 16-bit PCM to float32 in [-1, 1]."""
    if len(data) % 2:
        raise ValueError("PCM data must have an even number of bytes")
    return np.frombuffer(data, dtype="<i2").astype(np.float32) / 32768.0
