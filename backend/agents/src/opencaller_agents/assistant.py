"""
The `assistant` agent (ADR 0010, infra/deploy/telephony.md): LiveKit dispatches it to every room the assistant
numbers' dispatch rule creates. It answers the forwarded call with a voice pipeline (speech-to-text → language
model → text-to-speech, Silero for voice activity) and streams the conversation to the user's app over the data
channel. Run with `python -m opencaller_agents.assistant start`.
"""

from __future__ import annotations

import asyncio
import logging
import re
from collections.abc import Coroutine
from typing import Any

import aiohttp
from livekit import rtc
from livekit.agents import Agent, AgentServer, AgentSession, JobContext, RunContext, cli, function_tool, room_io
from livekit.plugins import silero

from . import providers
from .assistant_call import ScreeningCall, instructions
from .assistant_protocol import TOPIC, AppChannel, Details, parse_app_message
from .assistant_settings import AssistantSettings
from .server_api import AiohttpTransport, ServerApi

AGENT_NAME = "assistant"
log = logging.getLogger("opencaller.assistant")
server = AgentServer()
_NUMBER = re.compile(r"^\+?[0-9]{3,15}$")


def caller_number(attributes: dict[str, str]) -> str | None:
    """The caller's number from LiveKit's SIP attributes, or None when the network withheld it."""
    number = attributes.get("sip.phoneNumber", "").strip()
    return number if _NUMBER.match(number) else None


class ScreeningAgent(Agent):
    """The language model's side of the call: the prompt and two tools."""

    def __init__(self, call: ScreeningCall, language: str) -> None:
        super().__init__(instructions=instructions(language))
        self._call = call

    @function_tool
    async def record_details(
        self, context: RunContext, name: str | None, reason: str | None, urgency: str, likely_spam: bool
    ) -> str:
        """Records who is calling and why, for the person you are answering for.

        Args:
            name: The caller's name, if they gave it.
            reason: Why they are calling, in one short sentence.
            urgency: One of low, normal or high.
            likely_spam: True if the call looks like sales, a robocall, a scam or a survey.
        """
        await self._call.record(Details.of(name, reason, urgency, likely_spam))
        return "Recorded."

    @function_tool
    async def end_call(self, context: RunContext) -> None:
        """Ends the call. Call it only after you said goodbye."""
        await context.wait_for_playout()
        self._call.finish("done")


class SessionVoice:
    """[Voice] on LiveKit's AgentSession, listening to the caller's SIP participant only."""

    def __init__(self, ctx: JobContext, settings: AssistantSettings, caller_identity: str) -> None:
        self._ctx = ctx
        self._caller = caller_identity
        self._settings = settings
        self._started = False
        self.agent: Agent | None = None
        self.session = AgentSession(
            stt=providers.build_stt(settings),
            llm=providers.build_llm(settings),
            tts=providers.build_tts(settings),
            vad=silero.VAD.load(),
            user_away_timeout=None,
        )

    async def start(self) -> None:
        if self._started:
            return
        self._started = True
        await self.session.start(
            self.agent or Agent(instructions=instructions(self._settings.language)),
            room=self._ctx.room,
            # Our own channel carries the transcript to the user's app only; LiveKit's text output would go to
            # every participant. The call manages its own end.
            room_options=room_io.RoomOptions(
                participant_identity=self._caller, text_output=False, close_on_disconnect=False
            ),
            record=False,
        )

    async def say(self, text: str) -> None:
        await self.start()
        await self.session.say(text, allow_interruptions=False).wait_for_playout()

    async def stop(self) -> None:
        if self._started:
            await self.session.aclose()


@server.rtc_session(agent_name=AGENT_NAME)
async def entrypoint(ctx: JobContext) -> None:
    settings = AssistantSettings.from_env()
    tasks: set[asyncio.Task[Any]] = set()

    def spawn(work: Coroutine[Any, Any, Any]) -> None:
        task = asyncio.create_task(work)
        tasks.add(task)
        task.add_done_callback(tasks.discard)

    await ctx.connect()
    caller = await ctx.wait_for_participant(kind=rtc.ParticipantKind.PARTICIPANT_KIND_SIP)
    caller_left = asyncio.Event()
    ctx.room.on("participant_disconnected", lambda p: caller_left.set() if p.identity == caller.identity else None)
    ctx.room.on("disconnected", lambda *_: caller_left.set())

    async def send(payload: bytes, identities: list[str]) -> None:
        await ctx.room.local_participant.publish_data(
            payload, reliable=True, destination_identities=identities, topic=TOPIC
        )

    channel = AppChannel(send)
    voice = SessionVoice(ctx, settings, caller.identity)

    async def hang_up() -> None:
        # Deleting the room ends the SIP call; the forwarded caller hears the line close.
        await ctx.delete_room()

    async with aiohttp.ClientSession() as http:
        call = ScreeningCall(
            settings, ServerApi(AiohttpTransport(http, settings.api_url, settings.agent_token)), channel, voice, hang_up
        )
        voice.agent = ScreeningAgent(call, settings.language)

        def on_data(packet: rtc.DataPacket) -> None:
            sender = packet.participant.identity if packet.participant else None
            if packet.topic == TOPIC and channel.accepts(sender):
                action = parse_app_message(packet.data)
                if action is not None:
                    spawn(call.act(action))

        def on_joined(participant: rtc.RemoteParticipant) -> None:
            if participant.kind == rtc.ParticipantKind.PARTICIPANT_KIND_STANDARD:
                spawn(channel.joined(participant.identity))

        def on_heard(event: Any) -> None:
            if not event.is_final and event.transcript:
                spawn(channel.partial(event.transcript))

        def on_item(event: Any) -> None:
            role = getattr(event.item, "role", None)
            text = getattr(event.item, "text_content", None)
            if role in ("user", "assistant") and text:
                spawn(channel.line("caller" if role == "user" else "assistant", text))

        ctx.room.on("data_received", on_data)
        ctx.room.on("participant_connected", on_joined)
        voice.session.on("user_input_transcribed", on_heard)
        voice.session.on("conversation_item_added", on_item)

        dialed = caller.attributes.get("sip.trunkPhoneNumber", "")
        outcome = await call.run(dialed, ctx.room.name, caller_number(caller.attributes), caller_left)
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)
    log.info("Call done: claimed=%s reason=%s seconds=%s", outcome.claimed, outcome.reason, outcome.duration_s)
    ctx.shutdown("assistant call done")


if __name__ == "__main__":
    cli.run_app(server)
