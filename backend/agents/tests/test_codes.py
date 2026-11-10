import asyncio

from opencaller_agents.codes import CodeReader


async def feed(reader: CodeReader, digits: str) -> None:
    for digit in digits:
        reader.feed(digit)
        await asyncio.sleep(0)


async def test_the_code_ends_at_the_hash() -> None:
    reader = CodeReader()
    await feed(reader, "12345678#")
    assert await reader.read(1) == "12345678"


async def test_a_stray_tone_before_the_code_is_ignored() -> None:
    reader = CodeReader()
    await feed(reader, "912345678#")
    assert await reader.read(1) == "12345678"


async def test_star_and_short_codes_start_over() -> None:
    reader = CodeReader()
    await feed(reader, "123*1234#87654321#")
    assert await reader.read(1) == "87654321"


async def test_a_lost_hash_still_gives_the_code_when_time_runs_out() -> None:
    reader = CodeReader()
    await feed(reader, "12345678")
    assert await reader.read(0.05) == "12345678"


async def test_nothing_comes_back_without_a_code() -> None:
    reader = CodeReader()
    await feed(reader, "1234")
    assert await reader.read(0.05) is None
    reader.feed("A")  # not a DTMF digit we use
    assert await reader.read(0.05) is None
