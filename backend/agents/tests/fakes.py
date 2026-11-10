"""Stand-ins for the server, LiveKit's egress API and the room's audio track."""

from __future__ import annotations

from typing import Any

from livekit import api

from opencaller_agents.settings import RecorderSettings, S3Target

TOKEN = "t" * 32
SETTINGS = RecorderSettings(
    api_url="http://server.test",
    agent_token=TOKEN,
    s3=S3Target("http://minio.test:9000", "opencaller", "us-east-1", "access", "secret-key"),
    announce_delay_s=0,
    beep_interval_s=0,
    code_timeout_s=0.2,
)


class FakeTransport:
    """Answers like the server for one pending session with [code]; records every request."""

    def __init__(self, code: str = "12345678") -> None:
        self.code = code
        self.requests: list[tuple[str, str, Any]] = []
        self.jobs: list[dict[str, str]] = []

    async def send(self, method: str, path: str, body: dict[str, Any] | None) -> tuple[int, Any]:
        self.requests.append((method, path, body))
        if path == "/agent/v1/recording/claim":
            assert body is not None
            if body["code"] == self.code:
                self.code = "used"
                return 200, {"sessionId": "s-1", "objectKey": "recordings/s-1.ogg", "maxMinutes": 180}
            return 404, {"code": "code_not_found"}
        if path.endswith("/result"):
            return 204, None
        if path == "/agent/v1/transcripts/next":
            return (200, self.jobs.pop(0)) if self.jobs else (204, None)
        if path.startswith("/agent/v1/transcripts/"):
            return 204, None
        return 500, {"code": "internal_error"}

    def reports(self) -> list[Any]:
        return [body for _, path, body in self.requests if path.endswith("/result")]


class FakeEgress:
    """Track egress that completes when stopped, writing a file of [size] bytes and [duration_ns]."""

    def __init__(self, *, size: int = 4096, duration_ns: int = 61_500_000_000, fail: bool = False) -> None:
        self.started: list[api.TrackEgressRequest] = []
        self.stopped: list[str] = []
        self.status = api.EgressStatus.EGRESS_ACTIVE
        self.size = size
        self.duration_ns = duration_ns
        self.fail = fail

    async def start_track_egress(self, start: api.TrackEgressRequest) -> api.EgressInfo:
        self.started.append(start)
        return api.EgressInfo(egress_id="EG_1", status=api.EgressStatus.EGRESS_STARTING)

    async def stop_egress(self, stop: api.StopEgressRequest) -> api.EgressInfo:
        self.stopped.append(stop.egress_id)
        self.status = api.EgressStatus.EGRESS_FAILED if self.fail else api.EgressStatus.EGRESS_COMPLETE
        return api.EgressInfo(egress_id=stop.egress_id, status=api.EgressStatus.EGRESS_ENDING)

    async def list_egress(self, list: api.ListEgressRequest) -> api.ListEgressResponse:
        info = api.EgressInfo(egress_id="EG_1", status=self.status)
        if self.status == api.EgressStatus.EGRESS_COMPLETE:
            info.file_results.append(
                api.FileInfo(filename="recordings/s-1.ogg", size=self.size, duration=self.duration_ns)
            )
        return api.ListEgressResponse(items=[info])


class FakePlayer:
    def __init__(self) -> None:
        self.played: list[bytes] = []

    async def play(self, pcm: bytes) -> None:
        self.played.append(pcm)
