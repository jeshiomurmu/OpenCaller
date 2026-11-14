import numpy as np
import pytest

from voice_spoof.evaluate import eer
from voice_spoof.scorer import MODEL_SAMPLES, Scorer, fit, pcm16_to_float, spoof_probability


def test_audio_is_cut_or_repeated_like_aasist() -> None:
    assert fit(np.arange(70_000, dtype=np.float32)).size == MODEL_SAMPLES
    short = fit(np.array([1, 2, 3], dtype=np.float32), 7)
    assert short.tolist() == [1, 2, 3, 1, 2, 3, 1]
    with pytest.raises(ValueError):
        fit(np.array([], dtype=np.float32))


def test_probability_is_the_spoof_share_of_the_softmax() -> None:
    assert spoof_probability(np.array([0.0, 0.0])) == pytest.approx(0.5)
    assert spoof_probability(np.array([10.0, -10.0])) > 0.99
    assert spoof_probability(np.array([1000.0, -1000.0])) == pytest.approx(1.0)  # no overflow


def test_pcm16_is_read_little_endian() -> None:
    audio = pcm16_to_float(np.array([0, 16384, -32768], dtype="<i2").tobytes())
    assert audio.tolist() == [0.0, 0.5, -1.0]
    with pytest.raises(ValueError):
        pcm16_to_float(b"\x00")


def test_the_onnx_session_scores_audio(model_path) -> None:
    scorer = Scorer.load(model_path)
    assert scorer.score(np.full(64_000, 0.5, dtype=np.float32)) > 0.99
    assert scorer.score(np.full(64_000, -0.5, dtype=np.float32)) < 0.01


def test_equal_error_rate() -> None:
    assert eer(np.array([0.1, 0.2, 0.3]), np.array([0.7, 0.8, 0.9]))[0] == 0.0
    assert eer(np.array([0.1, 0.9]), np.array([0.1, 0.9]))[0] == pytest.approx(0.5)
    # one bona fide scored high and one spoof scored low out of four each: 25 %
    bonafide = np.array([0.1, 0.2, 0.3, 0.85])
    spoof = np.array([0.15, 0.7, 0.8, 0.9])
    assert eer(bonafide, spoof)[0] == pytest.approx(0.25)
