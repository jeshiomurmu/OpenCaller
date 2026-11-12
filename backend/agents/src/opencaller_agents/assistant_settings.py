"""
The assistant agent's settings (ADR 0010): which speech-to-text, language model and text-to-speech provider it uses,
chosen by the operator in the environment. Each provider's plugin reads its own key from its usual variable
(`OPENAI_API_KEY`, `DEEPGRAM_API_KEY`, `ELEVEN_API_KEY`, `GOOGLE_API_KEY`, `GOOGLE_APPLICATION_CREDENTIALS`);
an OpenAI-compatible self-hosted server is used by setting the matching `*_BASE_URL`.
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass

from .settings import SettingsError, _token, _url

STT_PROVIDERS = ("openai", "deepgram", "elevenlabs", "google")
LLM_PROVIDERS = ("openai", "google")
TTS_PROVIDERS = ("openai", "deepgram", "elevenlabs", "google")

# The variable that holds each provider's key, when the provider needs one in every setup.
_KEYS = {"deepgram": "DEEPGRAM_API_KEY", "elevenlabs": "ELEVEN_API_KEY"}


@dataclass(frozen=True)
class Provider:
    """One part of the voice pipeline: [name] is the provider; the rest is optional and provider-specific."""

    name: str
    model: str | None = None
    base_url: str | None = None
    voice: str | None = None


@dataclass(frozen=True)
class AssistantSettings:
    """The assistant agent's settings (infra/docker/compose.telephony.yml)."""

    api_url: str
    agent_token: str
    stt: Provider
    llm: Provider
    tts: Provider
    # BCP-47 language the assistant speaks and expects first (the caller may switch; see the prompt).
    language: str = "en"
    # Seconds before the end of the allowed time when the assistant starts saying goodbye.
    wrap_up_s: float = 20.0

    def __repr__(self) -> str:
        return (
            f"AssistantSettings(api_url={self.api_url!r}, agent_token=***, stt={self.stt!r}, llm={self.llm!r}, "
            f"tts={self.tts!r}, language={self.language!r})"
        )

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> AssistantSettings:
        """Reads the settings; raises [SettingsError] naming every problem."""
        env = os.environ if env is None else env
        problems: list[str] = []
        api_url = _url(env, "OPENCALLER_API_URL", problems)
        token = _token(env, problems)
        stt = _provider(env, "STT", STT_PROVIDERS, problems)
        llm = _provider(env, "LLM", LLM_PROVIDERS, problems)
        tts = _provider(env, "TTS", TTS_PROVIDERS, problems)
        for name in ("LIVEKIT_URL", "LIVEKIT_API_KEY", "LIVEKIT_API_SECRET"):
            if not env.get(name, "").strip():
                problems.append(f"{name} is required")
        language = env.get("ASSISTANT_LANGUAGE", "").strip() or "en"
        if not language.replace("-", "").isalnum() or len(language) > 16:
            problems.append("ASSISTANT_LANGUAGE must be a language tag such as en, bn or hi-IN")
        if problems:
            raise SettingsError(problems)
        return cls(api_url=api_url, agent_token=token, stt=stt, llm=llm, tts=tts, language=language)


def _provider(env: Mapping[str, str], part: str, allowed: tuple[str, ...], problems: list[str]) -> Provider:
    prefix = f"ASSISTANT_{part}"
    name = env.get(prefix, "").strip().lower() or "openai"
    if name not in allowed:
        problems.append(f"{prefix} must be one of {', '.join(allowed)}")
        return Provider(name)
    base_url = env.get(f"{prefix}_BASE_URL", "").strip() or None
    if base_url is not None and not base_url.startswith(("http://", "https://", "ws://", "wss://")):
        problems.append(f"{prefix}_BASE_URL must be a URL")
    key = _KEYS.get(name)
    if key and not env.get(key, "").strip():
        problems.append(f"{key} is required for {prefix}={name}")
    # OpenAI itself needs a key; an OpenAI-compatible server of the operator's own may not.
    if name == "openai" and base_url is None and not env.get("OPENAI_API_KEY", "").strip():
        problems.append(f"OPENAI_API_KEY is required for {prefix}=openai (or set {prefix}_BASE_URL)")
    vertex = env.get("GOOGLE_GENAI_USE_VERTEXAI", "").strip().lower() in ("1", "true")
    if name == "google" and part == "LLM" and not env.get("GOOGLE_API_KEY", "").strip() and not vertex:
        problems.append("GOOGLE_API_KEY (or GOOGLE_GENAI_USE_VERTEXAI) is required for ASSISTANT_LLM=google")
    if name == "google" and part != "LLM" and not env.get("GOOGLE_APPLICATION_CREDENTIALS", "").strip():
        problems.append(f"GOOGLE_APPLICATION_CREDENTIALS is required for {prefix}=google")
    return Provider(
        name=name,
        model=env.get(f"{prefix}_MODEL", "").strip() or None,
        base_url=base_url,
        voice=(env.get(f"{prefix}_VOICE", "").strip() or None) if part == "TTS" else None,
    )
