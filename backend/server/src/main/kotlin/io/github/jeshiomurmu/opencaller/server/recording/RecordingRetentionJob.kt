package io.github.jeshiomurmu.opencaller.server.recording

import io.github.jeshiomurmu.opencaller.server.config.RecordingConfig
import io.github.jeshiomurmu.opencaller.server.db.RecordingSessions
import io.github.jeshiomurmu.opencaller.server.db.Recordings
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.ACTIVE
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.FAILED
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.PENDING
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.TRANSCRIPT_FAILED
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.TRANSCRIPT_PENDING
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.TRANSCRIPT_WORKING
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStore
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** What one run of the recording-retention job did. */
@Serializable
data class RecordingRetentionResult(
    val recordingsDeleted: Int,
    val deleteFailures: Int,
    val sessionsExpired: Int,
    val abandonedSessions: Int,
    val sessionsPurged: Int,
    val transcriptsTimedOut: Int,
)

/**
 * Every [INTERVAL_MINUTES] (ADR 0009, TC-REC-001): deletes each recording [RecordingConfig.downloadGraceMinutes]
 * after the app confirmed its download (once a requested transcript is finished) and every recording by
 * [MAX_AGE_HOURS] after it was made, file first, then the row. Also fails sessions nobody claimed, removes the
 * file of a call whose agent never reported, gives up transcripts that hang and forgets sessions after two days.
 */
class RecordingRetentionJob(
    private val db: ServerDatabase,
    private val objects: ObjectStore,
    private val config: RecordingConfig,
    private val clock: Clock,
) {
    /** Runs once (the caller holds the leader lock). */
    suspend fun run(): RecordingRetentionResult {
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        val (deleted, failures) = deleteDue(now)
        val abandoned = abandoned(now)
        return db.query {
            RecordingRetentionResult(
                recordingsDeleted = deleted,
                deleteFailures = failures,
                sessionsExpired =
                    RecordingSessions.update({ (RecordingSessions.state eq PENDING) and (RecordingSessions.expiresAt less now) }) {
                        it[state] = FAILED
                        it[endedAt] = now
                    },
                abandonedSessions = abandoned,
                sessionsPurged = RecordingSessions.deleteWhere { createdAt less now.minusDays(SESSION_DAYS) },
                transcriptsTimedOut =
                    Recordings.update({
                        (Recordings.transcriptState eq TRANSCRIPT_WORKING) and
                            (Recordings.transcriptStartedAt less now.minusMinutes(TRANSCRIPT_TIMEOUT_MINUTES))
                    }) { it[transcriptState] = TRANSCRIPT_FAILED },
            )
        }
    }

    private suspend fun deleteDue(now: OffsetDateTime): Pair<Int, Int> {
        // A run every INTERVAL_MINUTES: taking rows that turn 24 h before the next run keeps every one under 24 h.
        val oldest = now.minusHours(MAX_AGE_HOURS).plusMinutes(INTERVAL_MINUTES)
        val downloadedBefore = now.minusMinutes(config.downloadGraceMinutes.toLong())
        val due =
            db.query {
                Recordings
                    .select(Recordings.id, Recordings.objectKey)
                    .where {
                        (Recordings.createdAt lessEq oldest) or
                            (
                                Recordings.downloadedAt.isNotNull() and (Recordings.downloadedAt lessEq downloadedBefore) and
                                    (Recordings.transcriptState notInList listOf(TRANSCRIPT_PENDING, TRANSCRIPT_WORKING))
                            )
                    }.limit(BATCH)
                    .map { it[Recordings.id] to it[Recordings.objectKey] }
            }
        var failures = 0
        val gone = mutableListOf<UUID>()
        for ((id, key) in due) {
            if (deleteFile(key)) gone += id else failures++
        }
        if (gone.isNotEmpty()) db.query { gone.forEach { id -> Recordings.deleteWhere { Recordings.id eq id } } }
        return gone.size to failures
    }

    // An active session whose call should long be over: the agent died before reporting; remove any file it left.
    private suspend fun abandoned(now: OffsetDateTime): Int {
        val cutoff = now.minusMinutes(RecordingLineService.MAX_MINUTES + ABANDON_AFTER_MINUTES)
        val stuck =
            db.query {
                RecordingSessions
                    .select(RecordingSessions.id)
                    .where { (RecordingSessions.state eq ACTIVE) and (RecordingSessions.claimedAt less cutoff) }
                    .limit(BATCH)
                    .map { it[RecordingSessions.id] }
            }
        val cleaned = stuck.filter { deleteFile(RecordingLineService.objectKey(it)) }
        if (cleaned.isNotEmpty()) {
            db.query {
                cleaned.forEach { id ->
                    RecordingSessions.update({ (RecordingSessions.id eq id) and (RecordingSessions.state eq ACTIVE) }) {
                        it[state] = FAILED
                        it[endedAt] = now
                    }
                }
            }
        }
        return cleaned.size
    }

    private suspend fun deleteFile(key: String): Boolean =
        try {
            objects.delete(key)
            true
        } catch (e: IOException) {
            log.warn("Could not delete a recording file (retrying next run): {}", e.message)
            false
        }

    /** Schedule and limits. */
    companion object {
        /** Name of the job. */
        const val JOB_NAME = "recording-retention"

        /** The job runs this often. */
        const val INTERVAL_MINUTES = 10L

        /** No recording stays longer than this. */
        const val MAX_AGE_HOURS = 24L
        private const val SESSION_DAYS = 2L
        private const val TRANSCRIPT_TIMEOUT_MINUTES = 60L
        private const val ABANDON_AFTER_MINUTES = 60L
        private const val BATCH = 500
    }
}

private val log = LoggerFactory.getLogger(RecordingRetentionJob::class.java)
