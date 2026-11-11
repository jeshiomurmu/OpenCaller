import asyncio

from fakes import SETTINGS, FakeEgress, FakePlayer, FakeTransport

from opencaller_agents import tones
from opencaller_agents.codes import CodeReader
from opencaller_agents.server_api import ServerApi
from opencaller_agents.session import RecordingCall

ROOM = "rec_+8809638000000_abc"


async def caller_track() -> str:
    return "TR_caller"


def call(transport: FakeTransport, egress: FakeEgress, player: FakePlayer, codes: CodeReader) -> RecordingCall:
    return RecordingCall(SETTINGS, ServerApi(transport), egress, player, codes, announcement=b"\x01\x00" * 480)


async def test_tc_rec_001_the_call_is_recorded_announced_and_reported() -> None:
    transport, egress, player, codes = FakeTransport(), FakeEgress(), FakePlayer(), CodeReader()
    ended = asyncio.Event()
    running = asyncio.create_task(call(transport, egress, player, codes).run(ROOM, caller_track, ended))
    for digit in "12345678#":
        codes.feed(digit)
    while not egress.started:
        await asyncio.sleep(0.01)
    while len(player.played) < 3:  # ready signal, beep, announcement
        await asyncio.sleep(0.01)
    ended.set()  # the caller hangs up
    outcome = await running

    assert outcome.claimed and outcome.recorded and outcome.reported
    assert egress.started[0].track_id == "TR_caller"
    assert egress.started[0].file.filepath == "recordings/s-1.ogg"
    assert player.played[0] == tones.ready_signal()
    assert player.played[1] == tones.recording_beep()  # both parties hear it after the merge
    assert player.played[2] == b"\x01\x00" * 480
    assert transport.reports() == [{"status": "complete", "sizeBytes": 4096, "durationMs": 61_500}]
    # The claim carries the code and the room, never a phone number of either party.
    claim = next(body for _, path, body in transport.requests if path.endswith("/claim"))
    assert claim == {"code": "12345678", "room": ROOM}


async def test_three_wrong_codes_get_the_rejection_and_nothing_is_recorded() -> None:
    transport, egress, player, codes = FakeTransport(code="99999999"), FakeEgress(), FakePlayer(), CodeReader()
    for code in ("11111111#", "22222222#", "33333333#", "99999999#"):
        for digit in code:
            codes.feed(digit)
    outcome = await call(transport, egress, player, codes).run(ROOM, caller_track, asyncio.Event())
    assert not outcome.claimed
    assert len([r for r in transport.requests if r[1].endswith("/claim")]) == 3
    assert egress.started == [] and transport.reports() == []
    assert player.played[-1] == tones.rejected_signal()


async def test_no_code_in_time_ends_without_a_claim() -> None:
    transport, egress, player = FakeTransport(), FakeEgress(), FakePlayer()
    outcome = await call(transport, egress, player, CodeReader()).run(ROOM, caller_track, asyncio.Event())
    assert not outcome.claimed and transport.requests == []


async def test_a_call_that_ends_while_waiting_for_the_code_stops_quietly() -> None:
    transport, egress, player = FakeTransport(), FakeEgress(), FakePlayer()
    ended = asyncio.Event()
    ended.set()
    outcome = await call(transport, egress, player, CodeReader()).run(ROOM, caller_track, ended)
    assert not outcome.claimed
    assert player.played == [tones.ready_signal()]  # no rejection tones into a closed call


async def test_a_session_whose_egress_cant_start_is_reported_failed() -> None:
    transport, player, codes = FakeTransport(), FakePlayer(), CodeReader()

    class Broken(FakeEgress):
        async def start_track_egress(self, start):  # type: ignore[no-untyped-def]
            raise RuntimeError("egress unavailable")

    for digit in "12345678#":
        codes.feed(digit)
    outcome = await call(transport, Broken(), player, codes).run(ROOM, caller_track, asyncio.Event())
    assert outcome.claimed and not outcome.recorded
    assert transport.reports() == [{"status": "failed", "sizeBytes": 0, "durationMs": 0}]


async def test_a_failed_upload_is_reported_failed() -> None:
    transport, player, codes = FakeTransport(), FakePlayer(), CodeReader()
    ended = asyncio.Event()
    for digit in "12345678#":
        codes.feed(digit)
    ended_soon = asyncio.get_running_loop().call_later(0.05, ended.set)
    outcome = await call(transport, FakeEgress(fail=True), player, codes).run(ROOM, caller_track, ended)
    ended_soon.cancel()
    assert outcome.claimed and not outcome.recorded
    assert transport.reports() == [{"status": "failed", "sizeBytes": 0, "durationMs": 0}]
