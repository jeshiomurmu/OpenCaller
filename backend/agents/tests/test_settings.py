import pytest

from opencaller_agents.settings import RecorderSettings, SettingsError, TranscriberSettings

ENV = {
    "OPENCALLER_API_URL": "http://127.0.0.1:8080/",
    "RECORDING_AGENT_TOKEN": "t" * 32,
    "RECORDING_S3_ENDPOINT": "http://127.0.0.1:9000",
    "RECORDING_S3_BUCKET": "opencaller",
    "RECORDING_S3_ACCESS_KEY": "opencaller",
    "RECORDING_S3_SECRET_KEY": "minio-secret-value",
    "LIVEKIT_URL": "ws://127.0.0.1:7880",
    "LIVEKIT_API_KEY": "opencaller",
    "LIVEKIT_API_SECRET": "s" * 32,
}


def test_a_complete_environment_loads_and_hides_secrets() -> None:
    settings = RecorderSettings.from_env(ENV)
    assert settings.api_url == "http://127.0.0.1:8080"
    assert settings.s3.region == "us-east-1"
    assert "minio-secret-value" not in repr(settings) and "t" * 32 not in repr(settings)


def test_every_problem_is_listed() -> None:
    env = dict(
        ENV, RECORDING_AGENT_TOKEN="short", OPENCALLER_API_URL="server:8080", RECORDING_ANNOUNCEMENT_WAV="/nope.wav"
    )
    del env["RECORDING_S3_BUCKET"]
    with pytest.raises(SettingsError) as error:
        RecorderSettings.from_env(env)
    problems = "\n".join(error.value.problems)
    for expected in ("at least 32", "must start with http", "RECORDING_S3_BUCKET is required", "doesn't exist"):
        assert expected in problems


def test_the_transcriber_defaults_to_the_small_model() -> None:
    assert TranscriberSettings.from_env(ENV).model == "small"
