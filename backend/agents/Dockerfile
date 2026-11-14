# OpenCaller's LiveKit agents (Phase 13, ADR 0009 and 0010): `python -m opencaller_agents.recorder start` (the
# recording line), `python -m opencaller_agents.transcriber` (optional transcripts with self-hosted Whisper) and
# `python -m opencaller_agents.assistant start` (the call assistant) and `python -m opencaller_agents.scanner start`
# (the voice scanner, ADR 0011).
# Versions are pinned in pyproject.toml; the base image is Docker Hub's official Python 3.13 slim image.
FROM python:3.13-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app
COPY pyproject.toml README.md ./
COPY src ./src
# The transcriber's (faster-whisper, CTranslate2) and the assistant's (provider plugins, Silero) extras are installed
# too, so one image serves every agent.
RUN pip install '.[transcripts,assistant]' \
    && useradd --create-home --uid 10001 agent \
    && install -d -o agent -g agent /home/agent/.cache/huggingface
USER agent
CMD ["python", "-m", "opencaller_agents.recorder", "start"]
