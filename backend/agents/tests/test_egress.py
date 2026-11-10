from fakes import SETTINGS, FakeEgress
from livekit import api

from opencaller_agents.egress import finish, track_egress_request


async def no_wait(_: float) -> None:
    return None


def test_the_request_records_the_callers_track_to_minio() -> None:
    request = track_egress_request("rec_+8809638000000_x", "TR_caller", "recordings/s-1.ogg", SETTINGS.s3)
    assert request.room_name == "rec_+8809638000000_x"
    assert request.track_id == "TR_caller"
    assert request.file.filepath == "recordings/s-1.ogg"
    assert request.file.disable_manifest
    s3 = request.file.s3
    assert (s3.endpoint, s3.bucket, s3.region, s3.access_key, s3.secret) == (
        "http://minio.test:9000",
        "opencaller",
        "us-east-1",
        "access",
        "secret-key",
    )
    assert s3.force_path_style  # MinIO


async def test_a_running_egress_is_stopped_and_its_file_reported() -> None:
    egress = FakeEgress(size=4096, duration_ns=61_500_000_000)
    result = await finish(egress, "EG_1", sleep=no_wait)
    assert egress.stopped == ["EG_1"]
    assert result.complete and result.size_bytes == 4096 and result.duration_ms == 61_500


async def test_an_egress_that_ended_by_itself_is_not_stopped_again() -> None:
    egress = FakeEgress()
    egress.status = api.EgressStatus.EGRESS_COMPLETE
    result = await finish(egress, "EG_1", sleep=no_wait)
    assert egress.stopped == []
    assert result.complete


async def test_a_failed_or_hanging_egress_is_not_complete() -> None:
    assert not (await finish(FakeEgress(fail=True), "EG_1", sleep=no_wait)).complete
    hanging = FakeEgress()
    hanging.fail = False

    async def stop_without_ending(stop: api.StopEgressRequest) -> api.EgressInfo:
        return api.EgressInfo(egress_id=stop.egress_id, status=api.EgressStatus.EGRESS_ENDING)

    hanging.stop_egress = stop_without_ending  # type: ignore[method-assign]
    hanging.status = api.EgressStatus.EGRESS_ENDING
    assert not (await finish(hanging, "EG_1", timeout_s=3, sleep=no_wait)).complete
