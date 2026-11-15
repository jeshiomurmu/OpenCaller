"""
The `scanner` agent (ADR 0011, infra/deploy/telephony.md): the recording line's dispatch rule sends it to every line
call next to the recorder; the one-time code decides which of the two stays. Run with
`python -m opencaller_agents.scanner start`.
"""

from __future__ import annotations

import asyncio
import functools
import logging
from collections.abc import AsyncIterator

import aiohttp
from livekit import rtc
from livekit.agents import AgentServer, AutoSubscribe, JobContext, cli

from . import tones
from .codes import CodeReader
from .room_audio import RoomPlayer
from .scan_call import ScanCall, Transcribe
from .server_api import AiohttpTransport, ServerApi
from .settings import ScannerSettings
from .speech import SAMPLE_RATE

AGENT_NAME = "scanner"
log = logging.getLogger("opencaller.scanner")
server = AgentServer()


class VoiceSpoofClient:
    """ml/voice-spoof's `POST /v1/score`: 4 s of 16 kHz mono 16-bit PCM in, the spoof probability out."""

    def __init__(self, http: aiohttp.ClientSession, base_url: str, token: str) -> None:
        self._http = http
        self._url = base_url.rstrip("/") + "/v1/score"
        self._headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/octet-stream"}

    async def score(self, pcm: bytes) -> float:
        timeout = aiohttp.ClientTimeout(total=15)
        try:
            async with self._http.post(self._url, data=pcm, headers=self._headers, timeout=timeout) as response:
                if response.status != 200:
                    raise ValueError(f"voice-spoof answered HTTP {response.status}")
                body = await response.json()
            probability = float(body["spoofProbability"])
        except (aiohttp.ClientError, KeyError, TypeError) as e:
            raise ValueError(f"voice-spoof unusable: {type(e).__name__}") from e
        if not 0.0 <= probability <= 1.0:
            raise ValueError("voice-spoof returned a probability out of range")
        return probability


@functools.cache  # one model per worker process, loaded at the first scan
def whisper(model_name: str) -> Transcribe:
    """faster-whisper on the CPU (int8), the language detected per sample; the text is only matched, never kept."""
    import numpy as np  # comes with faster-whisper (`pip install .[transcripts]`)
    from faster_whisper import WhisperModel

    model = WhisperModel(model_name, device="cpu", compute_type="int8")

    def run(pcm: bytes) -> str:
        audio = np.frombuffer(pcm, dtype="<i2").astype(np.float32) / 32768.0
        segments, _info = model.transcribe(audio)
        return " ".join(segment.text.strip() for segment in segments).strip()

    return run


async def caller_audio(caller: rtc.RemoteParticipant, room: rtc.Room) -> AsyncIterator[bytes]:
    """The SIP participant's audio (after the merge: the other party, while the app mutes the user) at 16 kHz mono."""
    track = next(
        (
            publication.track
            for publication in caller.track_publications.values()
            if publication.kind == rtc.TrackKind.KIND_AUDIO and publication.track is not None
        ),
        None,
    )
    if track is None:
        subscribed: asyncio.Future[rtc.Track] = asyncio.get_running_loop().create_future()

        def on_subscribed(t: rtc.Track, _p: rtc.RemoteTrackPublication, owner: rtc.RemoteParticipant) -> None:
            if owner.identity == caller.identity and t.kind == rtc.TrackKind.KIND_AUDIO and not subscribed.done():
                subscribed.set_result(t)

        room.on("track_subscribed", on_subscribed)
        try:
            track = await asyncio.wait_for(subscribed, timeout=10)
        finally:
            room.off("track_subscribed", on_subscribed)
    stream = rtc.AudioStream.from_track(track=track, sample_rate=SAMPLE_RATE, num_channels=1)
    try:
        async for event in stream:
            yield bytes(event.frame.data)
    finally:
        await stream.aclose()


@server.rtc_session(agent_name=AGENT_NAME)
async def entrypoint(ctx: JobContext) -> None:
    settings = ScannerSettings.from_env()
    codes = CodeReader()
    ended = asyncio.Event()

    ctx.room.on("sip_dtmf_received", lambda dtmf: codes.feed(dtmf.digit))
    # Audio only: the scanner listens to the line's caller; it never publishes anything until it has the call.
    await ctx.connect(auto_subscribe=AutoSubscribe.AUDIO_ONLY)
    caller = await ctx.wait_for_participant(kind=rtc.ParticipantKind.PARTICIPANT_KIND_SIP)
    ctx.room.on("participant_disconnected", lambda p: ended.set() if p.identity == caller.identity else None)
    ctx.room.on("disconnected", lambda *_: ended.set())

    source = rtc.AudioSource(tones.SAMPLE_RATE, 1)

    class LazyPlayer(RoomPlayer):
        """Publishes the scanner's track only when it first plays (after the claim), so a recording call never
        hears from it."""

        published = False

        async def play(self, pcm: bytes) -> None:
            if not self.published:
                track = rtc.LocalAudioTrack.create_audio_track("voice-scanner", source)
                await ctx.room.local_participant.publish_track(track)
                self.published = True
            await super().play(pcm)

    transcribe = await asyncio.to_thread(whisper, settings.whisper_model) if settings.whisper_model else None
    async with aiohttp.ClientSession() as http:
        call = ScanCall(
            settings,
            ServerApi(AiohttpTransport(http, settings.api_url, settings.agent_token)),
            LazyPlayer(source),
            codes,
            VoiceSpoofClient(http, settings.voice_spoof_url, settings.voice_spoof_token),
            transcribe,
        )
        outcome = await call.run(ctx.room.name, lambda: caller_audio(caller, ctx.room), ended)
    log.info("Scan done: claimed=%s reported=%s handed_over=%s", outcome.claimed, outcome.reported, outcome.handed_over)
    if outcome.claimed:
        # Deleting the room hangs up the line; the user's own call goes on and the app unmutes.
        ctx.delete_room()
    ctx.shutdown("voice scan done" if outcome.claimed else "not a voice scan")


if __name__ == "__main__":
    cli.run_app(server)
