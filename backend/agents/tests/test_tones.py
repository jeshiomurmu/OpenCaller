import array
import wave
from pathlib import Path

from opencaller_agents import tones


def samples(pcm: bytes) -> array.array:
    values = array.array("h")
    values.frombytes(pcm)
    return values


def test_the_beep_is_48_khz_and_fades_in_and_out() -> None:
    beep = samples(tones.recording_beep())
    assert len(beep) == 48_000 * 400 // 1000
    assert beep[0] == 0 and abs(beep[-1]) < 200
    assert max(beep) > 9_000  # audible: about 30 % of full scale


def test_frames_are_10_ms_and_padded() -> None:
    frames = list(tones.frames(tones.silence(25)))
    assert len(frames) == 3
    assert all(len(frame) == tones.SAMPLES_PER_FRAME * 2 for frame in frames)


def test_an_announcement_is_mixed_down_and_resampled(tmp_path: Path) -> None:
    path = tmp_path / "announcement.wav"
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(2)
        wav.setsampwidth(2)
        wav.setframerate(16_000)
        wav.writeframes(array.array("h", [1000, 3000] * 16_000).tobytes())  # one second
    pcm = samples(tones.read_wav(str(path)))
    assert len(pcm) == 48_000
    assert set(pcm) == {2000}


def test_only_16_bit_announcements(tmp_path: Path) -> None:
    path = tmp_path / "eight-bit.wav"
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(1)
        wav.setframerate(8_000)
        wav.writeframes(bytes(800))
    try:
        tones.read_wav(str(path))
        raise AssertionError("expected a ValueError")
    except ValueError as e:
        assert "16-bit" in str(e)
