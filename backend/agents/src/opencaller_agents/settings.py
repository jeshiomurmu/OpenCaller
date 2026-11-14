"""Settings from the environment; every missing or invalid variable is listed at once (like the server)."""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass

MIN_TOKEN_LENGTH = 32


class SettingsError(ValueError):
    """The environment is missing values or has invalid ones."""

    def __init__(self, problems: list[str]) -> None:
        super().__init__("Invalid agent configuration:\n" + "\n".join(f"  - {p}" for p in problems))
        self.problems = problems


@dataclass(frozen=True)
class S3Target:
    """Where LiveKit Egress uploads a recording (MinIO as the egress service reaches it)."""

    endpoint: str
    bucket: str
    region: str
    access_key: str
    secret_key: str

    def __repr__(self) -> str:  # never print the credentials
        return f"S3Target(endpoint={self.endpoint!r}, bucket={self.bucket!r}, region={self.region!r})"


@dataclass(frozen=True)
class RecorderSettings:
    """The recorder agent's settings (infra/docker/compose.telephony.yml)."""

    api_url: str
    agent_token: str
    s3: S3Target
    announcement_wav: str | None = None
    # Seconds between the claim and the announcement, so the app has merged the calls when it plays.
    announce_delay_s: float = 4.0
    # A short beep this often while recording (0 = only at the start).
    beep_interval_s: float = 15.0
    # How long the caller has to send the code, and how many codes a call may try.
    code_timeout_s: float = 20.0
    max_code_attempts: int = 3

    def __repr__(self) -> str:
        return f"RecorderSettings(api_url={self.api_url!r}, agent_token=***, s3={self.s3!r})"

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> RecorderSettings:
        """Reads the settings; raises [SettingsError] naming every problem."""
        env = os.environ if env is None else env
        problems: list[str] = []
        api_url = _url(env, "OPENCALLER_API_URL", problems)
        token = _token(env, problems)
        s3 = S3Target(
            endpoint=_url(env, "RECORDING_S3_ENDPOINT", problems),
            bucket=_required(env, "RECORDING_S3_BUCKET", problems),
            region=env.get("RECORDING_S3_REGION", "").strip() or "us-east-1",
            access_key=_required(env, "RECORDING_S3_ACCESS_KEY", problems),
            secret_key=_required(env, "RECORDING_S3_SECRET_KEY", problems),
        )
        for name in ("LIVEKIT_URL", "LIVEKIT_API_KEY", "LIVEKIT_API_SECRET"):
            _required(env, name, problems)
        announcement = env.get("RECORDING_ANNOUNCEMENT_WAV", "").strip() or None
        if announcement is not None and not os.path.isfile(announcement):
            problems.append("RECORDING_ANNOUNCEMENT_WAV names a file that doesn't exist")
        if problems:
            raise SettingsError(problems)
        return cls(api_url=api_url, agent_token=token, s3=s3, announcement_wav=announcement)


@dataclass(frozen=True)
class TranscriberSettings:
    """The transcriber's settings."""

    api_url: str
    agent_token: str
    model: str = "small"
    poll_s: float = 10.0

    def __repr__(self) -> str:
        return f"TranscriberSettings(api_url={self.api_url!r}, agent_token=***, model={self.model!r})"

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> TranscriberSettings:
        env = os.environ if env is None else env
        problems: list[str] = []
        api_url = _url(env, "OPENCALLER_API_URL", problems)
        token = _token(env, problems)
        if problems:
            raise SettingsError(problems)
        return cls(api_url=api_url, agent_token=token, model=env.get("WHISPER_MODEL", "").strip() or "small")


def _required(env: Mapping[str, str], name: str, problems: list[str]) -> str:
    value = env.get(name, "").strip()
    if not value:
        problems.append(f"{name} is required")
    return value


def _url(env: Mapping[str, str], name: str, problems: list[str]) -> str:
    value = _required(env, name, problems)
    if value and not value.startswith(("http://", "https://")):
        problems.append(f"{name} must start with http:// or https://")
    return value.rstrip("/")


def _token(env: Mapping[str, str], problems: list[str]) -> str:
    token = _required(env, "RECORDING_AGENT_TOKEN", problems)
    if token and len(token) < MIN_TOKEN_LENGTH:
        problems.append(f"RECORDING_AGENT_TOKEN must be at least {MIN_TOKEN_LENGTH} characters")
    return token


@dataclass(frozen=True)
class ScannerSettings:
    """The voice scanner's settings (ADR 0011, infra/docker/compose.telephony.yml)."""

    api_url: str
    agent_token: str
    voice_spoof_url: str
    voice_spoof_token: str
    # A faster-whisper model for the scam cues, or None to skip them (the voice result alone).
    whisper_model: str | None = "small"
    # Seconds between the claim and the beep, so the app has merged the calls when the scanner starts listening.
    merge_delay_s: float = 4.0
    # How many 4 s windows of speech are scored (their mean is reported) and how long the scanner listens at most.
    windows: int = 2
    max_listen_s: float = 40.0
    code_timeout_s: float = 20.0
    max_code_attempts: int = 3

    def __repr__(self) -> str:
        return (
            f"ScannerSettings(api_url={self.api_url!r}, agent_token=***, voice_spoof_url={self.voice_spoof_url!r}, "
            f"voice_spoof_token=***, whisper_model={self.whisper_model!r}, windows={self.windows})"
        )

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> ScannerSettings:
        """Reads the settings; raises [SettingsError] naming every problem."""
        env = os.environ if env is None else env
        problems: list[str] = []
        api_url = _url(env, "OPENCALLER_API_URL", problems)
        token = _token(env, problems)
        spoof_url = _url(env, "VOICE_SPOOF_URL", problems)
        spoof_token = _required(env, "VOICE_SPOOF_TOKEN", problems)
        if spoof_token and len(spoof_token) < MIN_TOKEN_LENGTH:
            problems.append(f"VOICE_SPOOF_TOKEN must be at least {MIN_TOKEN_LENGTH} characters")
        for name in ("LIVEKIT_URL", "LIVEKIT_API_KEY", "LIVEKIT_API_SECRET"):
            _required(env, name, problems)
        model = env.get("SCANNER_WHISPER_MODEL", "").strip() or "small"
        windows = env.get("SCANNER_WINDOWS", "").strip() or "2"
        if not windows.isdigit() or not 1 <= int(windows) <= 5:
            problems.append("SCANNER_WINDOWS must be a number in 1..5")
        if problems:
            raise SettingsError(problems)
        return cls(
            api_url=api_url,
            agent_token=token,
            voice_spoof_url=spoof_url,
            voice_spoof_token=spoof_token,
            whisper_model=None if model.lower() == "off" else model,
            windows=int(windows),
        )
