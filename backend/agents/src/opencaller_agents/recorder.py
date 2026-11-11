"""
The `recorder` agent (ADR 0009, infra/deploy/telephony.md): LiveKit dispatches it to every room the recording line's
dispatch rule creates. Run with `python -m opencaller_agents.recorder start`.
"""

from __future__ import annotations

import asyncio
import logging

import aiohttp
from livekit import rtc
from livekit.agents import AgentServer, AutoSubscribe, JobContext, cli

from . import tones
from .codes import CodeReader
from .server_api import AiohttpTransport, ServerApi
from .session import RecordingCall
from .settings import RecorderSettings

AGENT_NAME = "recorder"
log = logging.getLogger("opencaller.recorder")
server = AgentServer()


class RoomPlayer:
    """Plays PCM into a published audio track that everyone in the room (the merged call) hears."""

    def __init__(self, source: rtc.AudioSource) -> None:
        self._source = source

    async def play(self, pcm: bytes) -> None:
        for frame in tones.frames(pcm):
            await self._source.capture_frame(rtc.AudioFrame(frame, tones.SAMPLE_RATE, 1, tones.SAMPLES_PER_FRAME))
        await self._source.wait_for_playout()


async def caller_audio_track(participant: rtc.RemoteParticipant, room: rtc.Room) -> str:
    """The SIP participant's audio track id, once it is published."""
    published = asyncio.get_running_loop().create_future()

    def on_published(publication: rtc.RemoteTrackPublication, owner: rtc.RemoteParticipant) -> None:
        if (
            owner.identity == participant.identity
            and publication.kind == rtc.TrackKind.KIND_AUDIO
            and not published.done()
        ):
            published.set_result(publication.sid)

    room.on("track_published", on_published)
    try:
        for publication in participant.track_publications.values():
            if publication.kind == rtc.TrackKind.KIND_AUDIO:
                return publication.sid
        return await asyncio.wait_for(published, timeout=10)
    finally:
        room.off("track_published", on_published)


@server.rtc_session(agent_name=AGENT_NAME)
async def entrypoint(ctx: JobContext) -> None:
    settings = RecorderSettings.from_env()
    announcement = tones.read_wav(settings.announcement_wav) if settings.announcement_wav else None
    codes = CodeReader()
    ended = asyncio.Event()

    # The agent only listens for tones and plays sounds; Egress records, so nothing needs subscribing.
    ctx.room.on("sip_dtmf_received", lambda dtmf: codes.feed(dtmf.digit))
    await ctx.connect(auto_subscribe=AutoSubscribe.SUBSCRIBE_NONE)
    caller = await ctx.wait_for_participant(kind=rtc.ParticipantKind.PARTICIPANT_KIND_SIP)
    ctx.room.on("participant_disconnected", lambda p: ended.set() if p.identity == caller.identity else None)
    ctx.room.on("disconnected", lambda *_: ended.set())

    source = rtc.AudioSource(tones.SAMPLE_RATE, 1)
    track = rtc.LocalAudioTrack.create_audio_track("recording-line", source)
    await ctx.room.local_participant.publish_track(track)

    async with aiohttp.ClientSession() as http:
        call = RecordingCall(
            settings,
            ServerApi(AiohttpTransport(http, settings.api_url, settings.agent_token)),
            ctx.api.egress,
            RoomPlayer(source),
            codes,
            announcement=announcement,
        )
        outcome = await call.run(ctx.room.name, lambda: caller_audio_track(caller, ctx.room), ended)
    log.info("Call done: claimed=%s recorded=%s", outcome.claimed, outcome.recorded)
    # Deleting the room hangs up the line; the user's own call goes on.
    ctx.delete_room()
    ctx.shutdown("recording line call done")


if __name__ == "__main__":
    cli.run_app(server)
