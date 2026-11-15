import array
import math

from opencaller_agents.speech import SAMPLE_RATE, WINDOW_SAMPLES, SpeechWindows, voiced_fraction


def tone(seconds: float, amplitude: int = 8000) -> bytes:
    count = int(seconds * SAMPLE_RATE)
    return array.array("h", (round(amplitude * math.sin(i / 10)) for i in range(count))).tobytes()


def test_quiet_windows_are_dropped_and_speech_kept() -> None:
    windows = SpeechWindows()
    assert windows.feed(bytes(WINDOW_SAMPLES * 2)) == []  # 4 s of silence
    assert windows.dropped == 1
    kept = windows.feed(tone(2) + bytes(SAMPLE_RATE * 2 * 2))  # half speech, half silence: kept as it is
    assert len(kept) == 1 and len(kept[0]) == WINDOW_SAMPLES * 2
    assert windows.feed(tone(1)) == []  # not full yet


def test_voiced_fraction() -> None:
    assert voiced_fraction(tone(1), 300) == 1.0
    assert voiced_fraction(tone(1, amplitude=100), 300) == 0.0
    assert voiced_fraction(b"", 300) == 0.0
