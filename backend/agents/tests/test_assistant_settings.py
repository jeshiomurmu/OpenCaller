"""The assistant's provider settings and the pipeline built from them (fake plugins: no network, no keys)."""

from __future__ import annotations

from types import SimpleNamespace
from typing import Any

import pytest

from opencaller_agents import providers
from opencaller_agents.assistant_settings import AssistantSettings
from opencaller_agents.settings import SettingsError

BASE = {
    "OPENCALLER_API_URL": "http://server:8080/",
    "RECORDING_AGENT_TOKEN": "t" * 32,
    "LIVEKIT_URL": "ws://livekit:7880",
    "LIVEKIT_API_KEY": "key",
    "LIVEKIT_API_SECRET": "s" * 32,
}


def test_openai_for_everything_by_default() -> None:
    settings = AssistantSettings.from_env({**BASE, "OPENAI_API_KEY": "sk-test"})
    assert (settings.stt.name, settings.llm.name, settings.tts.name) == ("openai", "openai", "openai")
    assert settings.api_url == "http://server:8080"
    assert settings.language == "en"
    assert "t" * 32 not in repr(settings)


def test_each_part_has_its_own_provider_model_and_voice() -> None:
    settings = AssistantSettings.from_env(
        {
            **BASE,
            "ASSISTANT_STT": "Deepgram",
            "ASSISTANT_STT_MODEL": "nova-3",
            "DEEPGRAM_API_KEY": "dg",
            "ASSISTANT_LLM": "openai",
            "ASSISTANT_LLM_BASE_URL": "http://llm.internal:8000/v1",  # self-hosted: no OpenAI key needed
            "ASSISTANT_LLM_MODEL": "llama-3.3-70b",
            "ASSISTANT_TTS": "elevenlabs",
            "ASSISTANT_TTS_VOICE": "voice-1",
            "ELEVEN_API_KEY": "el",
            "ASSISTANT_LANGUAGE": "bn-BD",
        }
    )
    assert settings.stt.name == "deepgram" and settings.stt.model == "nova-3"
    assert settings.llm.base_url == "http://llm.internal:8000/v1" and settings.llm.model == "llama-3.3-70b"
    assert settings.tts.voice == "voice-1"
    assert settings.language == "bn-BD"


def test_every_problem_is_listed() -> None:
    with pytest.raises(SettingsError) as error:
        AssistantSettings.from_env(
            {
                "ASSISTANT_STT": "whisper.cpp",
                "ASSISTANT_LLM": "deepgram",  # Deepgram has no language model
                "ASSISTANT_TTS": "google",
                "ASSISTANT_TTS_BASE_URL": "tts.internal",
                "ASSISTANT_LANGUAGE": "bengali language",
            }
        )
    problems = "\n".join(error.value.problems)
    for expected in [
        "OPENCALLER_API_URL is required",
        "RECORDING_AGENT_TOKEN is required",
        "ASSISTANT_STT must be one of openai, deepgram, elevenlabs, google",
        "ASSISTANT_LLM must be one of openai, google",
        "ASSISTANT_TTS_BASE_URL must be a URL",
        "GOOGLE_APPLICATION_CREDENTIALS is required for ASSISTANT_TTS=google",
        "LIVEKIT_URL is required",
        "ASSISTANT_LANGUAGE must be a language tag",
    ]:
        assert expected in problems, problems


def test_keys_are_required_where_the_provider_needs_them() -> None:
    with pytest.raises(SettingsError) as error:
        AssistantSettings.from_env(
            {**BASE, "ASSISTANT_STT": "deepgram", "ASSISTANT_TTS": "elevenlabs", "ASSISTANT_LLM": "google"}
        )
    problems = "\n".join(error.value.problems)
    assert "DEEPGRAM_API_KEY is required for ASSISTANT_STT=deepgram" in problems
    assert "ELEVEN_API_KEY is required for ASSISTANT_TTS=elevenlabs" in problems
    assert "GOOGLE_API_KEY (or GOOGLE_GENAI_USE_VERTEXAI) is required for ASSISTANT_LLM=google" in problems
    assert "OPENAI_API_KEY" not in problems  # nothing uses OpenAI here


class FakePlugin:
    """Records how each plugin class was built."""

    def __init__(self, name: str, built: list[tuple[str, str, dict[str, Any]]]) -> None:
        self._name = name
        self._built = built

    def __getattr__(self, part: str) -> Any:
        return lambda **options: self._built.append((self._name, part, options)) or SimpleNamespace(options=options)


@pytest.fixture
def built(monkeypatch: pytest.MonkeyPatch) -> list[tuple[str, str, dict[str, Any]]]:
    calls: list[tuple[str, str, dict[str, Any]]] = []
    monkeypatch.setattr(providers, "_plugin", lambda name: FakePlugin(name, calls))
    monkeypatch.delenv("OPENAI_API_KEY", raising=False)
    return calls


def settings_for(env: dict[str, str]) -> AssistantSettings:
    return AssistantSettings.from_env({**BASE, **env})


def test_the_pipeline_uses_the_chosen_plugins(built: list[tuple[str, str, dict[str, Any]]]) -> None:
    settings = settings_for(
        {
            "ASSISTANT_STT": "google",
            "GOOGLE_APPLICATION_CREDENTIALS": "/run/secrets/gcp.json",
            "ASSISTANT_LLM": "google",
            "GOOGLE_API_KEY": "g",
            "ASSISTANT_LLM_MODEL": "gemini-2.5-flash",
            "ASSISTANT_TTS": "deepgram",
            "DEEPGRAM_API_KEY": "dg",
            "ASSISTANT_LANGUAGE": "hi-IN",
        }
    )
    providers.build_stt(settings)
    providers.build_llm(settings)
    providers.build_tts(settings)
    assert built == [
        ("google", "STT", {"languages": ["hi-IN"]}),
        ("google", "LLM", {"model": "gemini-2.5-flash"}),
        ("deepgram", "TTS", {}),
    ]


def test_a_self_hosted_openai_compatible_server_needs_no_key(built: list[tuple[str, str, dict[str, Any]]]) -> None:
    settings = settings_for(
        {
            "ASSISTANT_STT": "openai",
            "ASSISTANT_STT_BASE_URL": "http://whisper.internal/v1",
            "ASSISTANT_LLM_BASE_URL": "http://llm.internal/v1",
            "ASSISTANT_TTS": "elevenlabs",
            "ELEVEN_API_KEY": "el",
            "ASSISTANT_TTS_VOICE": "v-1",
            "ASSISTANT_LANGUAGE": "bn-BD",
        }
    )
    providers.build_stt(settings)
    providers.build_llm(settings)
    providers.build_tts(settings)
    assert built[0] == (
        "openai",
        "STT",
        {"base_url": "http://whisper.internal/v1", "api_key": "not-needed", "language": "bn"},
    )
    assert built[1] == ("openai", "LLM", {"base_url": "http://llm.internal/v1", "api_key": "not-needed"})
    assert built[2] == ("elevenlabs", "TTS", {"voice_id": "v-1", "language": "bn"})


def test_the_real_plugins_accept_the_options() -> None:
    """The option names match the 1.8.11 plugins' constructors (no network: building makes no request)."""
    from livekit.plugins import deepgram, elevenlabs, openai

    openai.STT(base_url="http://whisper.internal/v1", api_key="k", language="bn")
    openai.LLM(model="m", base_url="http://llm.internal/v1", api_key="k")
    openai.TTS(model="m", voice="alloy", base_url="http://tts.internal/v1", api_key="k")
    deepgram.STT(model="nova-3", language="en", api_key="k")
    deepgram.TTS(api_key="k")
    elevenlabs.STT(model_id="scribe_v1", language_code="bn", api_key="k")
    elevenlabs.TTS(voice_id="v", language="bn", api_key="k")
