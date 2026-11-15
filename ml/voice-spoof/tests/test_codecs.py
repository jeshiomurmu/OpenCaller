import shutil

import numpy as np
import pytest

from voice_spoof.codecs import CONDITIONS, degrade

pytestmark = pytest.mark.skipif(shutil.which("ffmpeg") is None, reason="ffmpeg is needed for the codecs")


@pytest.mark.parametrize("condition", CONDITIONS)
def test_every_condition_returns_16_khz_audio_of_about_the_same_length(condition, speech) -> None:
    out = degrade(speech, condition)
    assert out.dtype == np.float32
    assert abs(out.size - speech.size) < 16_000 * 0.1  # codec framing adds at most a few ms


def test_g711_band_limits_the_signal() -> None:
    t = np.arange(16_000) / 16_000
    tone = (0.3 * np.sin(2 * np.pi * 6_000 * t)).astype(np.float32)  # above the 4 kHz telephone band
    assert np.abs(degrade(tone, "g711")).mean() < 0.1 * np.abs(tone).mean()
    with pytest.raises(ValueError):
        degrade(tone, "amr")
