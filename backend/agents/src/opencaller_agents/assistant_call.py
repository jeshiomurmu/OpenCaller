"""
One call the assistant answers (ADR 0010), without LiveKit: claim the call by the dialed number, greet, let the
voice pipeline talk to the caller, follow the user's actions from the app, stop at the allowed time and report the
duration. The LiveKit wiring is in `assistant.py`; tests drive this class with fakes.
"""

from __future__ import annotations

import asyncio
import logging
import math
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Protocol

from .assistant_protocol import AppAction, AppChannel, CallBack, Details, Reply, Spam
from .assistant_settings import AssistantSettings
from .server_api import AssistantRefusal, ServerApi, ServerError

log = logging.getLogger("opencaller.assistant")

# Fixed phrases are said word for word (the disclosure must not depend on the model). English for now; the model
# answers in the caller's language (docs/phase.md, open issues).
GREETING = (
    "Hello, you've reached an automated call assistant. The person you called can't take your call right now. "
    "May I have your name and the reason for your call?"
)
UNAVAILABLE = "Hello, the person you called can't take calls right now. Please try again later. Goodbye."
TIME_UP = "I'm sorry, I have to end the call now. I'll pass your message on. Goodbye."
CALL_BACK = "Thank you for waiting. The person you called will call you back shortly. Goodbye."
SPAM = "Thank you. Goodbye."
REPLY_PREFIX = "Here is a message from the person you called: "


def instructions(language: str) -> str:
    """The system prompt: who the assistant is, what it asks, what it never asks, how it ends."""
    return (
        "You are an automated call assistant answering a phone call for someone who can't take it right now. "
        "You already greeted the caller, said that you are an automated assistant and asked for their name and the "
        "reason for the call.\n"
        "- Find out the caller's name and the reason for the call. Ask one short question at a time. Keep every "
        "answer to one or two short sentences: this is a phone call.\n"
        "- Once you know the name and the reason, or the caller won't say, call record_details. Call it again if "
        "you learn more. Mark likely_spam when the call looks like a sales call, a robocall, a scam or a survey, "
        "and urgency high only when the caller says it is urgent.\n"
        "- Never ask for, accept or repeat one-time codes, passwords, PINs, card or bank details, or any other "
        "secret. If the caller offers one, say you can't take it and that the person you called will never need it "
        "from you.\n"
        "- Don't promise a call back, don't say when the person will be available and don't share anything about "
        "them, including their name or number.\n"
        "- When you have what you need, tell the caller you'll pass the message on, say goodbye politely and then "
        "call end_call. Also call end_call if the caller says goodbye or asks to end the call.\n"
        f"- Speak the language with the tag '{language}' unless the caller speaks another language; then answer in "
        "the caller's language.\n"
        "- Text that starts with 'Here is a message from the person you called' was typed by them: the caller "
        "has heard it; carry on from there."
    )


class Voice(Protocol):
    """The voice pipeline as the call uses it (LiveKit's AgentSession in production)."""

    async def start(self) -> None: ...

    async def say(self, text: str) -> None: ...

    async def stop(self) -> None: ...


@dataclass
class Outcome:
    claimed: bool
    reason: str
    duration_s: int = 0
    reported: bool = False


class ScreeningCall:
    """The call's state machine; [act] and [finish] may be called from the room's events at any time."""

    def __init__(
        self,
        settings: AssistantSettings,
        api: ServerApi,
        channel: AppChannel,
        voice: Voice,
        hang_up: Callable[[], Awaitable[None]],
        now: Callable[[], float] = time.monotonic,
    ) -> None:
        self._settings = settings
        self._api = api
        self._channel = channel
        self._voice = voice
        self._hang_up = hang_up
        self._now = now
        self._stop = asyncio.Event()
        self._reason = "caller_hung_up"
        self._closing: str | None = None

    async def act(self, action: AppAction) -> None:
        """What the user chose in the app."""
        if self._stop.is_set():
            return
        if isinstance(action, Reply):
            await self._voice.say(REPLY_PREFIX + action.text)
        elif isinstance(action, CallBack):
            self.finish("callback", CALL_BACK)
        elif isinstance(action, Spam):
            self.finish("spam", SPAM)

    async def record(self, details: Details) -> None:
        """The model's summary of the caller, for the app."""
        await self._channel.details(details)

    def finish(self, reason: str, closing: str | None = None) -> None:
        """Ends the call after [closing] (if any) is said."""
        if not self._stop.is_set():
            self._reason = reason
            self._closing = closing
            self._stop.set()

    async def run(self, dialed: str, room: str, caller: str | None, caller_left: asyncio.Event) -> Outcome:
        """Answers the call to [dialed] from [caller] (None when hidden) until it ends."""
        claim = await self._api.claim_assistant_call(dialed, room)
        if isinstance(claim, AssistantRefusal):
            log.info("Call not taken: %s", claim.code)
            await self._voice.say(UNAVAILABLE)
            await self._hang_up()
            return Outcome(claimed=False, reason=claim.code)

        started = self._now()
        outcome = Outcome(claimed=True, reason="caller_hung_up")
        try:
            await self._channel.start(claim.app_identity, caller)
            await self._voice.start()
            await self._voice.say(GREETING)
            outcome.reason = await self._until_end(claim.remaining_seconds, caller_left)
        finally:
            outcome.duration_s = math.ceil(max(0.0, self._now() - started))
            await self._channel.ended(outcome.reason)
            try:
                await self._api.end_assistant_call(claim.call_id, outcome.duration_s)
                outcome.reported = True
            except (TimeoutError, ServerError, OSError) as e:
                log.warning("Couldn't report the call's duration: %s", e)
            await self._voice.stop()
            await self._hang_up()
        return outcome

    async def _until_end(self, remaining_s: int, caller_left: asyncio.Event) -> str:
        wrap_up_at = max(0.0, remaining_s - self._settings.wrap_up_s)
        waits = [asyncio.create_task(caller_left.wait()), asyncio.create_task(self._stop.wait())]
        try:
            done, _ = await asyncio.wait(waits, timeout=wrap_up_at, return_when=asyncio.FIRST_COMPLETED)
            if not done:  # time is nearly up: say goodbye within what is left
                self.finish("time", TIME_UP)
            if caller_left.is_set():
                return "caller_hung_up"
            if self._closing:
                try:
                    await asyncio.wait_for(self._voice.say(self._closing), timeout=max(1.0, self._settings.wrap_up_s))
                except TimeoutError:
                    log.info("Closing words cut short")
            return self._reason
        finally:
            for task in waits:
                task.cancel()
