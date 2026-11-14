package io.github.jeshiomurmu.opencaller.server.recording

import io.github.jeshiomurmu.opencaller.server.db.RecordingSessions
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.ACTIVE
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.ENDED
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.FAILED
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.PENDING
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * `GET /v1/lines/scanner/{id}`: `pending` until the line has the code, `active` while the scanner listens (merge now),
 * then `ended` with [spoofProbability] (the model's spoof score, 0–1; the app words it as a likelihood, never a
 * verdict) and [cues] (scam-cue ids from the transcript), or `failed`.
 */
@Serializable
data class ScanSessionView(
    val sessionId: String,
    val state: String,
    val spoofProbability: Float? = null,
    val cues: List<String> = emptyList(),
)

/** The scanner agent's result: [status] `complete` with [spoofProbability] and [cues], or `failed`. */
@Serializable
data class ScanResultRequest(
    val status: String,
    val spoofProbability: Double? = null,
    val cues: List<String> = emptyList(),
)

/**
 * Scam cues the scanner may report (ADR 0011): ids only, the app words them. The transcript itself never leaves the
 * agent.
 */
object ScanCues {
    /** Every cue id the agent may send (`backend/agents` `scam_cues.py` uses the same ids). */
    val ALL =
        setOf(
            "otp_request",
            "payment_request",
            "gift_card",
            "remote_access",
            "urgency",
            "authority",
            "secrecy",
            "prize",
            "account_problem",
            "personal_data",
        )
}

/** Scan sessions of the voice scanner (ADR 0011), which share the recording line's sessions table. */
class ScanService(
    private val db: ServerDatabase,
    private val clock: Clock,
) {
    /** [userId]'s scan [sessionId]. */
    suspend fun session(
        userId: UUID,
        sessionId: UUID,
    ): ScanSessionView =
        db.query {
            val row =
                RecordingSessions
                    .selectAll()
                    .where {
                        (RecordingSessions.id eq sessionId) and (RecordingSessions.userId eq userId) and
                            (RecordingSessions.kind eq RecordingKinds.SCAN)
                    }.singleOrNull() ?: throw sessionNotFound()
            val expired = row[RecordingSessions.state] == PENDING && !row[RecordingSessions.expiresAt].isAfter(now())
            ScanSessionView(
                sessionId = sessionId.toString(),
                state = if (expired) FAILED else row[RecordingSessions.state],
                spoofProbability = row[RecordingSessions.scanProbability],
                cues = row[RecordingSessions.scanCues]?.split(',')?.filter { it.isNotEmpty() }.orEmpty(),
            )
        }

    /**
     * The scanner's [result] for scan [sessionId]: 409 `session_not_active` unless it is an active scan, 400
     * `invalid_recording` for a probability outside 0–1 or an unknown cue.
     */
    suspend fun result(
        sessionId: UUID,
        result: ScanResultRequest,
    ) {
        val complete = result.status == COMPLETE
        requireValid(complete || result.status == FAILED) { invalidRecording("status must be complete or failed.") }
        val probability = result.spoofProbability
        requireValid(!complete || (probability != null && probability in 0.0..1.0)) {
            invalidRecording("spoofProbability must be between 0 and 1.")
        }
        requireValid(result.cues.size <= ScanCues.ALL.size && result.cues.all { it in ScanCues.ALL }) {
            invalidRecording("cues must be known cue ids.")
        }
        val now = now()
        val updated =
            db.query {
                RecordingSessions.update({
                    (RecordingSessions.id eq sessionId) and (RecordingSessions.state eq ACTIVE) and
                        (RecordingSessions.kind eq RecordingKinds.SCAN)
                }) {
                    it[state] = if (complete) ENDED else FAILED
                    it[endedAt] = now
                    if (complete) {
                        it[scanProbability] = checkNotNull(probability).toFloat()
                        it[scanCues] =
                            result.cues
                                .distinct()
                                .sorted()
                                .joinToString(",")
                    }
                }
            }
        if (updated == 0) throw ApiProblem(HttpStatusCode.Conflict, "session_not_active", "This scan session isn't active")
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private companion object {
        const val COMPLETE = "complete"
    }
}
