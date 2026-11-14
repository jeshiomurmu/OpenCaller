"""
The HTTP service the scanner agent calls (internal only; bearer `VOICE_SPOOF_TOKEN`).

    POST /v1/score    body: exactly 4 s of 16 kHz mono audio, either raw 16-bit little-endian PCM
                      (Content-Type: application/octet-stream or audio/L16) or a WAV file (audio/wav)
                      → {"spoofProbability": 0.0–1.0, "model": "AASIST", "seconds": 4.0}
    GET  /health      → {"status": "ok"}

Run: `VOICE_SPOOF_MODEL=model/aasist.onnx VOICE_SPOOF_TOKEN=… uvicorn voice_spoof.service:app --port 8090`.
Audio is scored in memory and never written anywhere.
"""

from __future__ import annotations

import hmac
import io
import os
from functools import lru_cache
from pathlib import Path
from typing import Annotated

import numpy as np
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.concurrency import run_in_threadpool

from .scorer import INPUT_SAMPLES, SAMPLE_RATE, Scorer, pcm16_to_float

MIN_TOKEN_LENGTH = 32
PCM_TYPES = {"application/octet-stream", "audio/l16"}
WAV_TYPES = {"audio/wav", "audio/x-wav", "audio/wave"}


@lru_cache(maxsize=1)
def _scorer() -> Scorer:
    return Scorer.load(
        Path(os.environ.get("VOICE_SPOOF_MODEL", "model/aasist.onnx")), int(os.environ.get("VOICE_SPOOF_THREADS", "0"))
    )


@lru_cache(maxsize=1)
def _token() -> str:
    token = os.environ.get("VOICE_SPOOF_TOKEN", "")
    if len(token) < MIN_TOKEN_LENGTH:
        raise RuntimeError(f"VOICE_SPOOF_TOKEN must be at least {MIN_TOKEN_LENGTH} characters")
    return token


def scorer() -> Scorer:
    return _scorer()


def authorized(authorization: str = Header(default="")) -> None:
    if not hmac.compare_digest(authorization.encode(), f"Bearer {_token()}".encode()):
        raise HTTPException(status_code=401, detail="A valid token is required")


def decode(body: bytes, content_type: str) -> np.ndarray:
    """The request's audio as float32, or 400/415 when it isn't 4 s of 16 kHz mono."""
    kind = content_type.split(";")[0].strip().lower()
    if kind in PCM_TYPES:
        if "rate=" in content_type and "rate=16000" not in content_type.replace(" ", ""):
            raise HTTPException(status_code=400, detail="audio/L16 must be 16 kHz")
        audio = pcm16_to_float(body)
    elif kind in WAV_TYPES:
        import soundfile as sf

        try:
            audio, rate = sf.read(io.BytesIO(body), dtype="float32", always_2d=True)
        except RuntimeError as e:
            raise HTTPException(status_code=400, detail="Unreadable WAV") from e
        if rate != SAMPLE_RATE or audio.shape[1] != 1:
            raise HTTPException(status_code=400, detail="WAV must be 16 kHz mono")
        audio = audio[:, 0]
    else:
        raise HTTPException(status_code=415, detail="Send 16-bit PCM (application/octet-stream) or WAV")
    if audio.size != INPUT_SAMPLES:
        raise HTTPException(status_code=400, detail=f"Send exactly {INPUT_SAMPLES} samples (4 s at 16 kHz)")
    return audio


app = FastAPI(title="OpenCaller voice anti-spoofing", version="1.0.0", docs_url=None, redoc_url=None)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/score", dependencies=[Depends(authorized)])
async def score(request: Request, model: Annotated[Scorer, Depends(scorer)]) -> dict[str, object]:
    body = await request.body()
    if len(body) > 2 * INPUT_SAMPLES + 4096:
        raise HTTPException(status_code=413, detail="Too large")
    audio = decode(body, request.headers.get("content-type", ""))
    probability = await run_in_threadpool(model.score, audio)
    return {"spoofProbability": round(probability, 4), "model": "AASIST", "seconds": INPUT_SAMPLES / SAMPLE_RATE}
