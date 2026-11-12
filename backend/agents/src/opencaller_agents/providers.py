"""
Builds the voice pipeline's parts from [AssistantSettings] with the LiveKit Agents plugins (1.8.11; parameters read
from the plugins' own constructors). A plugin is imported only when the operator picked it, so a missing optional
dependency of an unused provider can't stop the agent.
"""

from __future__ import annotations

import importlib
import os
from typing import Any

from .assistant_settings import AssistantSettings, Provider

# OpenAI-compatible servers of the operator's own often take no key; the client library still wants one.
_NO_KEY = "not-needed"


def _plugin(name: str) -> Any:
    return importlib.import_module(f"livekit.plugins.{name}")


def _given(**options: Any) -> dict[str, Any]:
    """Only the options that are set, so each plugin keeps its own defaults for the rest."""
    return {key: value for key, value in options.items() if value is not None}


def _openai_key(provider: Provider) -> str | None:
    if os.environ.get("OPENAI_API_KEY"):
        return None  # the plugin reads it itself
    return _NO_KEY if provider.base_url else None


def _language(settings: AssistantSettings) -> str:
    """The primary subtag (`bn` of `bn-BD`), which every provider understands."""
    return settings.language.split("-")[0].lower()


def build_stt(settings: AssistantSettings) -> Any:
    p = settings.stt
    if p.name == "openai":
        return _plugin("openai").STT(
            **_given(model=p.model, base_url=p.base_url, api_key=_openai_key(p), language=_language(settings))
        )
    if p.name == "deepgram":
        return _plugin("deepgram").STT(**_given(model=p.model, base_url=p.base_url, language=settings.language))
    if p.name == "elevenlabs":
        return _plugin("elevenlabs").STT(
            **_given(model_id=p.model, base_url=p.base_url, language_code=_language(settings))
        )
    if p.name == "google":
        return _plugin("google").STT(**_given(model=p.model, languages=[settings.language]))
    raise ValueError(f"Unknown STT provider {p.name}")


def build_llm(settings: AssistantSettings) -> Any:
    p = settings.llm
    if p.name == "openai":
        return _plugin("openai").LLM(**_given(model=p.model, base_url=p.base_url, api_key=_openai_key(p)))
    if p.name == "google":
        return _plugin("google").LLM(**_given(model=p.model))
    raise ValueError(f"Unknown LLM provider {p.name}")


def build_tts(settings: AssistantSettings) -> Any:
    p = settings.tts
    if p.name == "openai":
        return _plugin("openai").TTS(
            **_given(model=p.model, voice=p.voice, base_url=p.base_url, api_key=_openai_key(p))
        )
    if p.name == "deepgram":
        return _plugin("deepgram").TTS(**_given(model=p.model, base_url=p.base_url))
    if p.name == "elevenlabs":
        return _plugin("elevenlabs").TTS(
            **_given(voice_id=p.voice, model=p.model, base_url=p.base_url, language=_language(settings))
        )
    if p.name == "google":
        return _plugin("google").TTS(**_given(voice_name=p.voice, language=settings.language))
    raise ValueError(f"Unknown TTS provider {p.name}")
