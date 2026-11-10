"""LiveKit track egress for one recording: start it on the caller's audio track, stop it, read the result."""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from typing import Protocol

from livekit import api

from .settings import S3Target

log = logging.getLogger("opencaller.recorder")

TERMINAL = {
    api.EgressStatus.EGRESS_COMPLETE,
    api.EgressStatus.EGRESS_FAILED,
    api.EgressStatus.EGRESS_ABORTED,
    api.EgressStatus.EGRESS_LIMIT_REACHED,
}
_ACTIVE = {api.EgressStatus.EGRESS_STARTING, api.EgressStatus.EGRESS_ACTIVE}
_NANOS_PER_MILLI = 1_000_000


class EgressApi(Protocol):
    """The part of `LiveKitAPI.egress` the recorder uses (docs.livekit.io: Egress API)."""

    async def start_track_egress(self, start: api.TrackEgressRequest) -> api.EgressInfo: ...

    async def stop_egress(self, stop: api.StopEgressRequest) -> api.EgressInfo: ...

    async def list_egress(self, list: api.ListEgressRequest) -> api.ListEgressResponse: ...


@dataclass(frozen=True)
class EgressResult:
    """A finished egress: [complete] with the file's size and duration, or not."""

    complete: bool
    size_bytes: int = 0
    duration_ms: int = 0


def track_egress_request(room: str, track_id: str, object_key: str, s3: S3Target) -> api.TrackEgressRequest:
    """
    The caller's audio track to [object_key] in MinIO. Track egress re-encodes audio as Opus in OGG (no browser);
    MinIO needs path-style addressing. No manifest file: the server keeps what it needs.
    """
    return api.TrackEgressRequest(
        room_name=room,
        track_id=track_id,
        file=api.DirectFileOutput(
            filepath=object_key,
            disable_manifest=True,
            s3=api.S3Upload(
                access_key=s3.access_key,
                secret=s3.secret_key,
                region=s3.region,
                endpoint=s3.endpoint,
                bucket=s3.bucket,
                force_path_style=True,
            ),
        ),
    )


async def finish(
    egress: EgressApi,
    egress_id: str,
    *,
    timeout_s: float = 120.0,
    poll_s: float = 1.0,
    sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
) -> EgressResult:
    """
    Stops [egress_id] if it still runs (it also ends by itself when the caller's track goes away) and waits until
    LiveKit reports the upload's outcome.
    """
    info = await _info(egress, egress_id)
    if info is not None and info.status in _ACTIVE:
        try:
            info = await egress.stop_egress(api.StopEgressRequest(egress_id=egress_id))
        except Exception as e:  # noqa: BLE001 - it may have ended between the two calls; the poll below decides
            log.info("stop_egress: %s", type(e).__name__)
    waited = 0.0
    while info is None or info.status not in TERMINAL:
        if waited >= timeout_s:
            log.warning("Egress didn't finish in %.0f s", timeout_s)
            return EgressResult(complete=False)
        await sleep(poll_s)
        waited += poll_s
        info = await _info(egress, egress_id)
    return _result(info)


async def _info(egress: EgressApi, egress_id: str) -> api.EgressInfo | None:
    response = await egress.list_egress(api.ListEgressRequest(egress_id=egress_id))
    return next((item for item in response.items if item.egress_id == egress_id), None)


def _result(info: api.EgressInfo) -> EgressResult:
    if info.status != api.EgressStatus.EGRESS_COMPLETE:
        log.warning("Egress ended with status %s", api.EgressStatus.Name(info.status))
        return EgressResult(complete=False)
    files = list(info.file_results)
    if not files and info.HasField("file"):
        files = [info.file]
    if not files:
        return EgressResult(complete=False)
    return EgressResult(complete=True, size_bytes=files[0].size, duration_ms=files[0].duration // _NANOS_PER_MILLI)
