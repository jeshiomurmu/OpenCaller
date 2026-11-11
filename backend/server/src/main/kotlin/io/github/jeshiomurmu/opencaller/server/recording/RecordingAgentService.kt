package io.github.jeshiomurmu.opencaller.server.recording

import io.github.jeshiomurmu.opencaller.server.db.RecordingSessions
import io.github.jeshiomurmu.opencaller.server.db.Recordings
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.push.PushKind
import io.github.jeshiomurmu.opencaller.server.push.PushService
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.ACTIVE
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.ENDED
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.FAILED
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.TRANSCRIPT_DONE
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.TRANSCRIPT_FAILED
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.TRANSCRIPT_OFF
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.TRANSCRIPT_PENDING
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.TRANSCRIPT_WORKING
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStore
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * What the agents report (`/agent/v1/…`, bearer `RECORDING_AGENT_TOKEN`): the recorder's result for a session, and
 * the transcriber's queue. A finished recording reaches the app as a push carrying only a random item id (ADR 0007).
 */
class RecordingAgentService(
    private val db: ServerDatabase,
    private val objects: ObjectStore,
    private val push: PushService,
    private val clock: Clock,
) {
    /**
     * The recorder's [result] for session [sessionId]. `complete`: the file must be at the session's object key; the
     * recording is stored with the size the store reports and the user is notified. `failed`: any partial file is
     * deleted. A session that isn't active answers 409 `session_not_active`.
     */
    suspend fun result(
        sessionId: UUID,
        result: RecordingResultRequest,
    ) {
        requireValid(result.status in setOf(COMPLETE, FAILED)) { invalidRecording("status must be complete or failed.") }
        requireValid(result.durationMs in 0..MAX_DURATION_MS) { invalidRecording("durationMs is out of range.") }
        val userId = db.query { activeSession(sessionId) }
        val key = RecordingLineService.objectKey(sessionId)
        val stored = if (result.status == COMPLETE) objects.head(key) else null
        if (stored == null) {
            deleteQuietly(key)
            db.query { finish(sessionId, FAILED) }
            return
        }
        val recordingId = UUID.randomUUID()
        val now = now()
        db.query {
            if (finish(sessionId, ENDED) == 0) throw notActive()
            val transcribe =
                RecordingSessions
                    .selectAll()
                    .where { RecordingSessions.id eq sessionId }
                    .single()[RecordingSessions.transcribe]
            Recordings.insert {
                it[id] = recordingId
                it[Recordings.sessionId] = sessionId
                it[Recordings.userId] = userId
                it[objectKey] = key
                it[sizeBytes] = stored.size
                it[durationMs] = result.durationMs
                it[transcriptState] = if (transcribe) TRANSCRIPT_PENDING else TRANSCRIPT_OFF
                it[createdAt] = now
            }
        }
        push.notify(userId, PushKind.RECORDING_READY, recordingId, READY_TTL_SECONDS, urgent = false)
    }

    /** The oldest recording waiting for a transcript, now marked as being worked on, or `null`. */
    suspend fun nextTranscript(): TranscriptJob? {
        val (id, key) =
            db.query {
                val row =
                    Recordings
                        .selectAll()
                        .where { Recordings.transcriptState eq TRANSCRIPT_PENDING }
                        .orderBy(Recordings.createdAt, SortOrder.ASC)
                        .limit(1)
                        .forUpdate()
                        .singleOrNull() ?: return@query null
                Recordings.update({ Recordings.id eq row[Recordings.id] }) {
                    it[transcriptState] = TRANSCRIPT_WORKING
                    it[transcriptStartedAt] = now()
                }
                row[Recordings.id] to row[Recordings.objectKey]
            } ?: return null
        return TranscriptJob(id.toString(), objects.presignGet(key, TRANSCRIPT_URL_SECONDS).toString())
    }

    /** The transcriber's [result] for recording [id] (which must be in work); 404 if it is gone or not in work. */
    suspend fun transcript(
        id: UUID,
        result: TranscriptResult,
    ) {
        val text = result.text
        requireValid(result.failed || (text != null && text.length <= MAX_TRANSCRIPT_CHARS)) {
            invalidRecording("Send text (at most $MAX_TRANSCRIPT_CHARS characters) or failed=true.")
        }
        val updated =
            db.query {
                Recordings.update({ (Recordings.id eq id) and (Recordings.transcriptState eq TRANSCRIPT_WORKING) }) {
                    it[transcriptState] = if (result.failed) TRANSCRIPT_FAILED else TRANSCRIPT_DONE
                    it[transcript] = if (result.failed) null else text?.filter { c -> !c.isISOControl() || c == '\n' }
                }
            }
        if (updated == 0) throw recordingNotFound()
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.activeSession(sessionId: UUID): UUID {
        val row =
            RecordingSessions
                .selectAll()
                .where { RecordingSessions.id eq sessionId }
                .singleOrNull() ?: throw sessionNotFound()
        if (row[RecordingSessions.state] != ACTIVE) throw notActive()
        return row[RecordingSessions.userId]
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.finish(
        sessionId: UUID,
        state: String,
    ): Int =
        RecordingSessions.update({ (RecordingSessions.id eq sessionId) and (RecordingSessions.state eq ACTIVE) }) {
            it[RecordingSessions.state] = state
            it[endedAt] = now()
        }

    private suspend fun deleteQuietly(key: String) {
        try {
            objects.delete(key)
        } catch (e: IOException) {
            // The retention job deletes the session's key again later.
            log.warn("Could not delete a failed recording's file: {}", e.message)
        }
    }

    private fun notActive() = ApiProblem(HttpStatusCode.Conflict, "session_not_active", "This recording session isn't active")

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    /** Limits. */
    companion object {
        /** A `recording_ready` push is worth delivering while the recording exists. */
        const val READY_TTL_SECONDS = 24 * 60 * 60

        /** How long the transcriber's download link works. */
        const val TRANSCRIPT_URL_SECONDS = 30 * 60

        /** The longest transcript kept (about three hours of speech). */
        const val MAX_TRANSCRIPT_CHARS = 200_000
        private const val COMPLETE = "complete"
        private const val MAX_DURATION_MS = (RecordingLineService.MAX_MINUTES + 10) * 60_000L
    }
}

private val log = LoggerFactory.getLogger(RecordingAgentService::class.java)
