package io.github.jeshiomurmu.opencaller.core.model

/**
 * The recording line for one Record tap (docs/prd.md F-UT-03, ADR 0009): dial [number], send [code] as tones, then
 * merge the calls once the session is [RecordingSessionState.ACTIVE]. The code expires at [expiresAtMillis].
 */
data class RecordingLine(
    val sessionId: String,
    val number: PhoneNumber,
    val code: String,
    val expiresAtMillis: Long,
)

/** Where a recording session is: waiting for the code, recording, finished or failed. */
enum class RecordingSessionState {
    /** The line hasn't received the code yet. */
    PENDING,

    /** The line has the code and records: merge the calls now. */
    ACTIVE,

    /** The call ended and the recording is stored; [RecordingSession.recordingId] names it. */
    ENDED,

    /** Nothing was recorded (wrong or expired code, upload failed). */
    FAILED,
}

/** A session's state and, once it ended, its recording. */
data class RecordingSession(
    val state: RecordingSessionState,
    val recordingId: String? = null,
)

/** Whether a recording gets a transcript (self-hosted Whisper on the server, only when the user turned it on). */
enum class TranscriptState {
    OFF,
    PENDING,
    WORKING,
    DONE,
    FAILED,
}

/** A recording still on the server; it is deleted there by [deleteByMillis] at the latest. */
data class ServerRecording(
    val id: String,
    val createdAtMillis: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val transcript: TranscriptState,
    val downloaded: Boolean,
    val deleteByMillis: Long,
)

/** A recording saved on this phone (the only lasting copy); [otherParty] is who the call was with, if known. */
data class SavedRecording(
    val id: String,
    val otherParty: PhoneNumber?,
    val filePath: String,
    val durationMs: Long,
    val transcript: String?,
    val createdAtMillis: Long,
)
