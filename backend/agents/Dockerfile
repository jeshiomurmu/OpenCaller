# OpenCaller's LiveKit agents (Phase 13, ADR 0009): `python -m opencaller_agents.recorder start` (the recording
# line) and `python -m opencaller_agents.transcriber` (optional transcripts with self-hosted Whisper).
# Versions are pinned in pyproject.toml; the base image is Docker Hub's official Python 3.13 slim image.
FROM python:3.13-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app
COPY pyproject.toml README.md ./
COPY src ./src
# The transcriber's extra (faster-whisper, CTranslate2) is installed too, so one image serves both services.
RUN pip install '.[transcripts]' \
    && useradd --create-home --uid 10001 agent
USER agent
CMD ["python", "-m", "opencaller_agents.recorder", "start"]
