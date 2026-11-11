"""The assistant agent (TC-AST-001 without LiveKit): the claim, the greeting, the app's actions, the cap and the report."""

from __future__ import annotations

import asyncio
import json
from typing import Any

import pytest

from opencaller_agents import assistant_call
from opencaller_agents.assistant import ScreeningAgent, caller_number
from opencaller_agents.assistant_call import ScreeningCall, instructions
from opencaller_agents.assistant_protocol import AppChannel, CallBack, Details, Reply, Spam, parse_app_message
from opencaller_agents.assistant_settings import AssistantSettings, Provider
from opencaller_agents.server_api import ServerApi

TOKEN = "t" * 32
SETTINGS = AssistantSettings(
    api_url="http://server.test",
    agent_token=TOKEN,
    stt=Provider("deepgram"),
    llm=Provider("openai"),
    tts=Provider("elevenlabs", voice="v1"),
    wrap_up_s=0.05,
)
DIALED = "+8809638000001"
CALLER = "+8801711000099"
APP = "user-1"


class AssistantServer:
    """Answers like the server's assistant endpoints; records every request."""

    def __init__(self, *, remaining: int = 300, refuse: str | None = None) -> None:
        self.remaining = remaining
        self.refuse = refuse
        self.requests: list[tuple[str, str, Any]] = []

    async def send(self, method: str, path: str, body: dict[str, Any] | None) -> tuple[int, Any]:
        self.requests.append((method, path, body))
        if path == "/agent/v1/assistant/calls":
            if self.refuse == "assistant_limit":
                return 429, {"code": "assistant_limit"}
            if self.refuse:
                return 404, {"code": self.refuse}
            return 200, {"callId": "c-1", "appIdentity": APP, "remainingSeconds": self.remaining}
        if path == "/agent/v1/assistant/calls/c-1/end":
            return 204, None
        return 500, {"code": "internal_error"}

    def ends(self) -> list[Any]:
        return [body for _, path, body in self.requests if path.endswith("/end")]


class FakeVoice:
    """Stands in for the STT → LLM → TTS pipeline: records what it said."""

    def __init__(self) -> None:
        self.said: list[str] = []
        self.started = False
        self.stopped = False

    async def start(self) -> None:
        self.started = True

    async def say(self, text: str) -> None:
        self.said.append(text)

    async def stop(self) -> None:
        self.stopped = True


class Room:
    """Collects the data messages the agent sends, with their destinations."""

    def __init__(self) -> None:
        self.sent: list[tuple[dict[str, Any], list[str]]] = []
        self.hung_up = 0

    async def send(self, payload: bytes, identities: list[str]) -> None:
        self.sent.append((json.loads(payload), identities))

    async def hang_up(self) -> None:
        self.hung_up += 1

    def kinds(self) -> list[str]:
        return [message["t"] for message, _ in self.sent]


class Clock:
    def __init__(self) -> None:
        self.t = 1000.0

    def __call__(self) -> float:
        return self.t


def make(server: AssistantServer, room: Room, voice: FakeVoice, clock: Clock | None = None) -> ScreeningCall:
    channel = AppChannel(room.send)
    return ScreeningCall(SETTINGS, ServerApi(server), channel, voice, room.hang_up, now=clock or Clock())


async def until(condition: Any) -> None:
    for _ in range(500):
        if condition():
            return
        await asyncio.sleep(0.01)
    raise AssertionError("condition never became true")


async def test_tc_ast_001_the_assistant_answers_streams_to_the_app_and_reports_the_minutes() -> None:
    server, room, voice, clock = AssistantServer(), Room(), FakeVoice(), Clock()
    call = make(server, room, voice, clock)
    left = asyncio.Event()
    running = asyncio.create_task(call.run(DIALED, "ast_abc", CALLER, left))
    await until(lambda: assistant_call.GREETING in voice.said)

    # The disclosure is said word for word; the app hears who calls.
    assert voice.started
    assert voice.said[0].startswith("Hello, you've reached an automated call assistant")
    assert room.sent[0] == ({"t": "hello", "v": 1, "caller": CALLER, "callerHidden": False}, [APP])

    # The conversation and the model's details reach the app, and only the app.
    await call._channel.line("caller", "Hi, this is Rahim from the clinic about tomorrow's appointment.")
    await call.record(Details.of("Rahim", "Tomorrow's appointment", "high", False))
    await call.act(Reply("I'll call you in ten minutes"))
    assert voice.said[-1] == assistant_call.REPLY_PREFIX + "I'll call you in ten minutes"
    assert all(identities == [APP] for _, identities in room.sent)

    # The caller hangs up after 70 s: the server gets the duration, nothing else.
    clock.t += 69.4
    left.set()
    outcome = await running
    assert outcome.claimed and outcome.reported
    assert outcome.duration_s == 70
    assert server.ends() == [{"durationSeconds": 70}]
    assert room.kinds()[-1] == "ended" and room.sent[-1][0]["reason"] == "caller_hung_up"
    assert room.hung_up == 1 and voice.stopped
    claim = server.requests[0]
    assert claim == ("POST", "/agent/v1/assistant/calls", {"number": DIALED, "room": "ast_abc"})
    assert CALLER not in json.dumps(server.requests)  # the caller's number never goes to the server


async def test_call_back_tells_the_caller_and_hangs_up() -> None:
    server, room, voice = AssistantServer(), Room(), FakeVoice()
    call = make(server, room, voice)
    running = asyncio.create_task(call.run(DIALED, "ast_abc", CALLER, asyncio.Event()))
    await until(lambda: assistant_call.GREETING in voice.said)
    await call.act(CallBack())
    outcome = await running
    assert outcome.reason == "callback"
    assert voice.said[-1] == assistant_call.CALL_BACK
    assert room.sent[-1][0] == {"t": "ended", "reason": "callback"}
    assert room.hung_up == 1


async def test_spam_ends_the_call() -> None:
    server, room, voice = AssistantServer(), Room(), FakeVoice()
    call = make(server, room, voice)
    running = asyncio.create_task(call.run(DIALED, "ast_abc", None, asyncio.Event()))
    await until(lambda: voice.started)
    await call.act(Spam())
    await call.act(Reply("too late"))  # nothing is said after the call ended
    outcome = await running
    assert outcome.reason == "spam"
    assert "too late" not in " ".join(voice.said)
    assert room.sent[0][0]["callerHidden"] is True


async def test_the_assistant_says_goodbye_before_the_minutes_run_out() -> None:
    server, room, voice = AssistantServer(remaining=0), Room(), FakeVoice()
    outcome = await make(server, room, voice).run(DIALED, "ast_abc", CALLER, asyncio.Event())
    assert outcome.reason == "time"
    assert voice.said[-1] == assistant_call.TIME_UP
    assert server.ends() == [{"durationSeconds": 0}]


@pytest.mark.parametrize("code", ["number_not_found", "assistant_limit", "supporter_required"])
async def test_a_refused_call_hears_that_the_person_is_unavailable(code: str) -> None:
    server, room, voice = AssistantServer(refuse=code), Room(), FakeVoice()
    outcome = await make(server, room, voice).run(DIALED, "ast_abc", CALLER, asyncio.Event())
    assert not outcome.claimed and outcome.reason == code
    assert voice.said == [assistant_call.UNAVAILABLE]
    assert room.sent == []  # no app to tell, and the caller's number goes nowhere
    assert room.hung_up == 1 and server.ends() == []


async def test_an_app_that_joins_late_gets_the_whole_conversation() -> None:
    room = Room()
    channel = AppChannel(room.send)
    assert not channel.accepts(APP)  # nothing is accepted before the claim
    await channel.start(APP, CALLER)
    await channel.line("assistant", "Hello, you've reached an automated call assistant.")
    await channel.line("caller", "It's Rahim.")
    await channel.details(Details.of("Rahim", None, "urgent!", None))
    room.sent.clear()
    await channel.joined("somebody-else")
    assert room.sent == []
    await channel.joined(APP)
    assert [m["t"] for m, _ in room.sent] == ["hello", "line", "line", "details"]
    assert room.sent[-1][0] == {
        "t": "details",
        "name": "Rahim",
        "reason": None,
        "urgency": "normal",
        "likelySpam": False,
    }
    assert channel.accepts(APP) and not channel.accepts("sip_caller") and not channel.accepts(None)


def test_app_messages_are_parsed_strictly() -> None:
    assert parse_app_message(b'{"t":"reply","text":"  Call me   later "}') == Reply("Call me later")
    assert parse_app_message(json.dumps({"t": "reply", "text": "x" * 500}).encode()) == Reply("x" * 200)
    assert parse_app_message(b'{"t":"callback"}') == CallBack()
    assert parse_app_message(b'{"t":"spam"}') == Spam()
    for junk in [b'{"t":"reply","text":""}', b'{"t":"transfer"}', b"[1]", b"\xff", b"not json", b"x" * 5000]:
        assert parse_app_message(junk) is None


def test_the_prompt_forbids_secrets_and_names_the_language() -> None:
    prompt = instructions("bn")
    assert "one-time codes, passwords, PINs, card or bank details" in prompt
    assert "record_details" in prompt and "end_call" in prompt
    assert "'bn'" in prompt
    assert "automated" in assistant_call.GREETING


def test_the_caller_number_is_read_from_sip_attributes() -> None:
    assert caller_number({"sip.phoneNumber": "+8801711000099"}) == "+8801711000099"
    assert caller_number({"sip.phoneNumber": "anonymous"}) is None
    assert caller_number({}) is None


async def test_the_model_tools_feed_the_call() -> None:
    server, room, voice = AssistantServer(), Room(), FakeVoice()
    call = make(server, room, voice)
    agent = ScreeningAgent(call, "en")
    assert {tool.info.name for tool in agent.tools} >= {"record_details", "end_call"}
    await call._channel.start(APP, CALLER)
    await agent.record_details(None, "Rahim", "Delivery", "low", True)  # type: ignore[arg-type]
    assert room.sent[-1][0]["likelySpam"] is True
