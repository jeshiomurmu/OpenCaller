"""The voice scanner's call (ADR 0011, TC-SCAN-001) with a fake server, room audio, scorer and speech-to-text."""

import array
import asyncio
import math
from collections.abc import AsyncIterator

import pytest
from fakes import TOKEN, FakePlayer, FakeTransport

from opencaller_agents import tones
from opencaller_agents.codes import CodeReader
from opencaller_agents.scan_call import ScanCall
from opencaller_agents.server_api import ServerApi
from opencaller_agents.settings import ScannerSettings, SettingsError
from opencaller_agents.speech import SAMPLE_RATE, WINDOW_SAMPLES

ROOM = "rec_+8809638000000_abc"
SETTINGS = ScannerSettings(
    api_url="http://server.test",
    agent_token=TOKEN,
    voice_spoof_url="http://voice-spoof.test:8000",
    voice_spoof_token="v" * 32,
    merge_delay_s=0,
    code_timeout_s=0.2,
)


def speech(seconds: float) -> bytes:
    """A loud 220 Hz tone standing in for a voice (16 kHz mono 16-bit)."""
    count = int(seconds * SAMPLE_RATE)
    return array.array(
        "h", (round(8000 * math.sin(2 * math.pi * 220 * i / SAMPLE_RATE)) for i in range(count))
    ).tobytes()


def silence(seconds: float) -> bytes:
    return bytes(2 * int(seconds * SAMPLE_RATE))


class FakeScorer:
    def __init__(self, *scores: float, fail: bool = False) -> None:
        self.scores = list(scores)
        self.windows: list[bytes] = []
        self.fail = fail

    async def score(self, pcm: bytes) -> float:
        if self.fail:
            raise ValueError("voice-spoof answered HTTP 503")
        self.windows.append(pcm)
        return self.scores.pop(0)


def audio_of(*chunks: bytes):
    def open_stream() -> AsyncIterator[bytes]:
        async def stream() -> AsyncIterator[bytes]:
            for chunk in chunks:
                for i in range(0, len(chunk), 640):  # 20 ms frames, like LiveKit's stream
                    yield chunk[i : i + 640]
                    await asyncio.sleep(0)
            await asyncio.Event().wait()  # the call goes on

        return stream()

    return open_stream


def scan(transport, player, codes, scorer, transcribe=None) -> ScanCall:
    return ScanCall(SETTINGS, ServerApi(transport), player, codes, scorer, transcribe)


def send_code(codes: CodeReader) -> None:
    for digit in "12345678#":
        codes.feed(digit)


async def test_tc_scan_001_two_windows_are_scored_and_only_the_outcome_is_reported() -> None:
    transport, player, codes = FakeTransport(kind="scan"), FakePlayer(), CodeReader()
    scorer = FakeScorer(0.9, 0.7)
    heard: list[bytes] = []

    def transcribe(pcm: bytes) -> str:
        heard.append(pcm)
        return "This is the police. Read me the OTP right now and don't tell anyone."

    send_code(codes)
    audio = audio_of(silence(4), speech(9))  # a quiet window first: dropped
    outcome = await scan(transport, player, codes, scorer, transcribe).run(ROOM, audio, asyncio.Event())

    assert outcome.claimed and outcome.reported
    assert player.played == [tones.scan_beep()]  # the only sound: both parties hear the scan start
    assert [len(w) for w in scorer.windows] == [WINDOW_SAMPLES * 2] * 2
    assert heard == [b"".join(scorer.windows)]
    assert outcome.probability == pytest.approx(0.8)
    assert transport.reports() == [
        {"status": "complete", "spoofProbability": 0.8, "cues": ["otp_request", "urgency", "authority", "secrecy"]}
    ]
    claim = next(body for _, path, body in transport.requests if path.endswith("/claim"))
    assert claim == {"code": "12345678", "room": ROOM, "kind": "scan"}  # no phone number
    assert any(path == "/agent/v1/scanner/sessions/s-1/result" for _, path, _ in transport.requests)


async def test_a_recording_code_is_left_to_the_recorder_in_silence() -> None:
    transport, player, codes = FakeTransport(kind="record"), FakePlayer(), CodeReader()
    send_code(codes)
    outcome = await scan(transport, player, codes, FakeScorer()).run(ROOM, audio_of(), asyncio.Event())
    assert outcome.handed_over and not outcome.claimed
    assert player.played == [] and transport.reports() == []
    assert transport.code == "12345678"


async def test_a_wrong_code_gets_no_sound_from_the_scanner() -> None:
    transport, player, codes = FakeTransport(code="87654321", kind="scan"), FakePlayer(), CodeReader()
    for _ in range(3):
        send_code(codes)
    outcome = await scan(transport, player, codes, FakeScorer()).run(ROOM, audio_of(), asyncio.Event())
    assert not outcome.claimed and player.played == []  # the recorder plays the rejection


async def test_a_call_that_ends_before_enough_speech_is_reported_failed() -> None:
    transport, player, codes = FakeTransport(kind="scan"), FakePlayer(), CodeReader()
    ended = asyncio.Event()
    send_code(codes)
    running = asyncio.create_task(scan(transport, player, codes, FakeScorer(0.5)).run(ROOM, audio_of(speech(2)), ended))
    while not player.played:
        await asyncio.sleep(0.01)
    ended.set()
    outcome = await running
    assert outcome.claimed and outcome.probability is None
    assert transport.reports() == [{"status": "failed"}]


async def test_a_scorer_failure_is_reported_failed_and_cues_without_whisper_are_empty() -> None:
    transport, player, codes = FakeTransport(kind="scan"), FakePlayer(), CodeReader()
    send_code(codes)
    outcome = await scan(transport, player, codes, FakeScorer(fail=True)).run(
        ROOM, audio_of(speech(9)), asyncio.Event()
    )
    assert outcome.reported and transport.reports() == [{"status": "failed"}]

    transport, codes = FakeTransport(kind="scan"), CodeReader()
    send_code(codes)
    outcome = await scan(transport, FakePlayer(), codes, FakeScorer(0.1, 0.2)).run(
        ROOM, audio_of(speech(9)), asyncio.Event()
    )
    assert transport.reports() == [{"status": "complete", "spoofProbability": 0.15, "cues": []}]


async def test_a_transcription_failure_keeps_the_voice_result() -> None:
    def broken(_pcm: bytes) -> str:
        raise RuntimeError("model missing")

    transport, codes = FakeTransport(kind="scan"), CodeReader()
    send_code(codes)
    await scan(transport, FakePlayer(), codes, FakeScorer(0.6, 0.6), broken).run(
        ROOM, audio_of(speech(9)), asyncio.Event()
    )
    assert transport.reports() == [{"status": "complete", "spoofProbability": 0.6, "cues": []}]


def test_settings_need_the_voice_spoof_service_and_hide_the_tokens() -> None:
    base = {
        "OPENCALLER_API_URL": "http://127.0.0.1:8080",
        "RECORDING_AGENT_TOKEN": TOKEN,
        "LIVEKIT_URL": "ws://127.0.0.1:7880",
        "LIVEKIT_API_KEY": "k",
        "LIVEKIT_API_SECRET": "s",
    }
    with pytest.raises(SettingsError) as error:
        ScannerSettings.from_env(base | {"VOICE_SPOOF_TOKEN": "short", "SCANNER_WINDOWS": "9"})
    assert "VOICE_SPOOF_URL is required" in error.value.problems
    assert "VOICE_SPOOF_TOKEN must be at least 32 characters" in error.value.problems
    assert "SCANNER_WINDOWS must be a number in 1..5" in error.value.problems

    settings = ScannerSettings.from_env(
        base
        | {"VOICE_SPOOF_URL": "http://127.0.0.1:8090/", "VOICE_SPOOF_TOKEN": "v" * 32, "SCANNER_WHISPER_MODEL": "off"}
    )
    assert settings.voice_spoof_url == "http://127.0.0.1:8090"
    assert settings.whisper_model is None and settings.windows == 2
    assert TOKEN not in repr(settings) and "v" * 32 not in repr(settings)
