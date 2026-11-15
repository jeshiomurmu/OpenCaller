import io

import numpy as np
import pytest
from fastapi.testclient import TestClient

from voice_spoof import service
from voice_spoof.scorer import Scorer

TOKEN = "t" * 32


@pytest.fixture
def client(model_path, monkeypatch):
    monkeypatch.setenv("VOICE_SPOOF_TOKEN", TOKEN)
    service._token.cache_clear()
    service.app.dependency_overrides[service.scorer] = lambda: Scorer.load(model_path)
    yield TestClient(service.app)
    service.app.dependency_overrides.clear()
    service._token.cache_clear()


def pcm(value: float, samples: int = 64_000) -> bytes:
    return (np.full(samples, value) * 32767).astype("<i2").tobytes()


def post(client, body: bytes, content_type: str = "application/octet-stream", token: str = TOKEN):
    return client.post(
        "/v1/score", content=body, headers={"Content-Type": content_type, "Authorization": f"Bearer {token}"}
    )


def test_four_seconds_of_pcm_get_a_probability(client) -> None:
    response = post(client, pcm(0.5))
    assert response.status_code == 200
    body = response.json()
    assert body["spoofProbability"] > 0.99 and body["model"] == "AASIST" and body["seconds"] == 4.0
    assert post(client, pcm(-0.5), "audio/L16; rate=16000").json()["spoofProbability"] < 0.01


def test_wav_must_be_16_khz_mono(client) -> None:
    import soundfile as sf

    def wav(rate: int, channels: int, seconds: int = 4) -> bytes:
        buffer = io.BytesIO()
        frames = np.full((rate * seconds, channels), 0.5, dtype=np.float32)
        sf.write(buffer, frames, rate, format="WAV", subtype="PCM_16")
        return buffer.getvalue()

    assert post(client, wav(16_000, 1), "audio/wav").status_code == 200
    assert post(client, wav(8_000, 1), "audio/wav").status_code == 400
    assert post(client, wav(16_000, 2, seconds=2), "audio/wav").status_code == 400
    assert post(client, b"not a wav", "audio/wav").status_code == 400


def test_wrong_length_type_rate_or_token_is_refused(client) -> None:
    assert post(client, pcm(0.5, 32_000)).status_code == 400
    assert post(client, pcm(0.5), "audio/L16; rate=8000").status_code == 400
    assert post(client, pcm(0.5), "audio/mpeg").status_code == 415
    assert post(client, pcm(0.5) * 3).status_code == 413
    assert post(client, pcm(0.5), token="wrong").status_code == 401
    assert client.post("/v1/score", content=pcm(0.5)).status_code == 401
    assert client.get("/health").json() == {"status": "ok"}
