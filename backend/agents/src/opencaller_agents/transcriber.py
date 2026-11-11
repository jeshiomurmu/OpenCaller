"""
Transcripts for users who turned them on (ADR 0009): self-hosted Whisper through faster-whisper, on this machine.
Run with `python -m opencaller_agents.transcriber`. The audio never leaves the operator's servers.
"""

from __future__ import annotations

import asyncio
import logging
import os
import tempfile
from collections.abc import Awaitable, Callable

import aiohttp

from .server_api import AiohttpTransport, ServerApi
from .settings import TranscriberSettings

log = logging.getLogger("opencaller.transcriber")
MAX_DOWNLOAD_BYTES = 512 * 1024 * 1024

Transcribe = Callable[[str], str]
Download = Callable[[str, str], Awaitable[None]]


async def transcribe_next(server: ServerApi, download: Download, transcribe: Transcribe) -> bool:
    """Transcribes one waiting recording; False when none waits. A failure is reported, never retried."""
    job = await server.next_transcript()
    if job is None:
        return False
    with tempfile.TemporaryDirectory() as folder:
        path = os.path.join(folder, "recording.ogg")
        try:
            await download(job.url, path)
            text = await asyncio.to_thread(transcribe, path)
        except Exception as e:  # noqa: BLE001 - any failure ends this job as failed
            log.warning("Transcript failed: %s", type(e).__name__)
            await server.put_transcript(job.recording_id, None)
            return True
    await server.put_transcript(job.recording_id, text)
    return True


def whisper(model_name: str) -> Transcribe:
    """faster-whisper on the CPU (int8); the language is detected per recording."""
    from faster_whisper import WhisperModel  # optional dependency: `pip install .[transcripts]`

    model = WhisperModel(model_name, device="cpu", compute_type="int8")

    def run(path: str) -> str:
        segments, _info = model.transcribe(path)
        return " ".join(segment.text.strip() for segment in segments).strip()

    return run


def downloader(http: aiohttp.ClientSession) -> Download:
    async def download(url: str, path: str) -> None:
        received = 0
        async with http.get(url, timeout=aiohttp.ClientTimeout(total=300)) as response:
            response.raise_for_status()
            file = await asyncio.to_thread(open, path, "wb")
            try:
                async for chunk in response.content.iter_chunked(64 * 1024):
                    received += len(chunk)
                    if received > MAX_DOWNLOAD_BYTES:
                        raise ValueError("recording too large")
                    await asyncio.to_thread(file.write, chunk)
            finally:
                await asyncio.to_thread(file.close)

    return download


async def main() -> None:
    logging.basicConfig(level=logging.INFO)
    settings = TranscriberSettings.from_env()
    transcribe = whisper(settings.model)
    async with aiohttp.ClientSession() as http:
        server = ServerApi(AiohttpTransport(http, settings.api_url, settings.agent_token))
        while True:
            try:
                worked = await transcribe_next(server, downloader(http), transcribe)
            except (aiohttp.ClientError, TimeoutError) as e:
                log.warning("Server unreachable: %s", type(e).__name__)
                worked = False
            if not worked:
                await asyncio.sleep(settings.poll_s)


if __name__ == "__main__":
    asyncio.run(main())
