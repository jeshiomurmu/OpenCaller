package io.github.jeshiomurmu.opencaller.server.recording

import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/**
 * `POST /v1/lines/recording`: dial [number], send [code] as tones once the line answers, then merge the calls.
 * The session expires at [expiresAt] if no call claims it.
 */
@Serializable
data class RecordingLineView(
    val sessionId: String,
    val number: String,
    val code: String,
    val expiresAt: String,
)

/** `GET /v1/lines/recording/{id}`: `pending`, `active` (the line has the code; merge now), `ended` or `failed`. */
@Serializable
data class RecordingSessionView(
    val sessionId: String,
    val state: String,
    val recordingId: String? = null,
)

/**
 * A recording the app can download until [deleteBy]. [transcript] is `off`, `pending`, `working`, `done` or
 * `failed`.
 */
@Serializable
data class RecordingView(
    val id: String,
    val createdAt: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val transcript: String,
    val downloaded: Boolean,
    val deleteBy: String,
)

/** `GET /v1/recordings/{id}/download`: a short-lived link to the file and, when done, the transcript text. */
@Serializable
data class RecordingDownload(
    val url: String,
    val contentType: String,
    val sizeBytes: Long,
    val expiresInSeconds: Int,
    val transcript: String? = null,
)

/** `GET`/`PUT /v1/recordings/settings`. */
@Serializable
data class RecordingSettings(
    val transcripts: Boolean,
)

/** The agent read [code] from the caller's tones in LiveKit room [room]. */
@Serializable
data class ClaimRequest(
    val code: String,
    val room: String = "",
)

/** The session the agent records; [objectKey] is where Egress must write the file. */
@Serializable
data class ClaimResponse(
    val sessionId: String,
    val objectKey: String,
    val maxMinutes: Int,
)

/** The agent's result: [status] `complete` (with the file's size and duration) or `failed`. */
@Serializable
data class RecordingResultRequest(
    val status: String,
    val sizeBytes: Long = 0,
    val durationMs: Long = 0,
)

/** A recording waiting for its transcript: fetch the file from [url] (short-lived). */
@Serializable
data class TranscriptJob(
    val recordingId: String,
    val url: String,
)

/** The transcriber's result: [text], or [failed]. */
@Serializable
data class TranscriptResult(
    val text: String? = null,
    val failed: Boolean = false,
)

/** The recording line's services. */
class RecordingRouteServices(
    val lines: RecordingLineService,
    val recordings: RecordingService,
    val agents: RecordingAgentService,
    /** `RECORDING_AGENT_TOKEN`; without it the `/agent/…` routes don't exist. */
    val agentToken: String?,
)

/** Session and transcript states as stored. */
internal object RecordingStates {
    const val PENDING = "pending"
    const val ACTIVE = "active"
    const val ENDED = "ended"
    const val FAILED = "failed"

    const val TRANSCRIPT_OFF = "off"
    const val TRANSCRIPT_PENDING = "pending"
    const val TRANSCRIPT_WORKING = "working"
    const val TRANSCRIPT_DONE = "done"
    const val TRANSCRIPT_FAILED = "failed"
}

/** 403 `recording_unavailable`: off on this server, not approved in the user's country or no line there. */
internal fun recordingUnavailable(detail: String) =
    ApiProblem(HttpStatusCode.Forbidden, "recording_unavailable", "Recording isn't available", detail)

/** 404 `recording_not_found` (also for another user's recording). */
internal fun recordingNotFound() = ApiProblem(HttpStatusCode.NotFound, "recording_not_found", "No such recording")

/** 400 `invalid_recording` with [detail]. */
internal fun invalidRecording(detail: String) = ApiProblem(HttpStatusCode.BadRequest, "invalid_recording", "Invalid request", detail)
