from pathlib import Path

from fakes import FakeTransport

from opencaller_agents.server_api import ServerApi
from opencaller_agents.transcriber import transcribe_next


async def download(url: str, path: str) -> None:
    Path(path).write_bytes(b"OggS")


async def test_nothing_waits() -> None:
    transport = FakeTransport()
    assert not await transcribe_next(ServerApi(transport), download, lambda path: "")


async def test_a_recording_is_transcribed_and_the_text_delivered() -> None:
    transport = FakeTransport()
    transport.jobs.append({"recordingId": "r-1", "url": "http://minio.test/recordings/s-1.ogg?sig"})
    seen: list[bytes] = []

    def transcribe(path: str) -> str:
        seen.append(Path(path).read_bytes())
        return "Hello."

    assert await transcribe_next(ServerApi(transport), download, transcribe)
    assert seen == [b"OggS"]
    assert transport.requests[-1] == ("PUT", "/agent/v1/transcripts/r-1", {"text": "Hello."})


async def test_a_failure_is_reported_once() -> None:
    transport = FakeTransport()
    transport.jobs.append({"recordingId": "r-2", "url": "http://minio.test/x"})

    def broken(path: str) -> str:
        raise RuntimeError("model missing")

    assert await transcribe_next(ServerApi(transport), download, broken)
    assert transport.requests[-1] == ("PUT", "/agent/v1/transcripts/r-2", {"failed": True})
