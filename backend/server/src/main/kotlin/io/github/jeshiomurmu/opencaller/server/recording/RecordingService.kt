package io.github.jeshiomurmu.opencaller.server.recording

import io.github.jeshiomurmu.opencaller.server.config.RecordingConfig
import io.github.jeshiomurmu.opencaller.server.db.Recordings
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.profile.activeUser
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.TRANSCRIPT_DONE
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.TRANSCRIPT_PENDING
import io.github.jeshiomurmu.opencaller.server.recording.RecordingStates.TRANSCRIPT_WORKING
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStore
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The app's side of recordings (ADR 0009): list, a short-lived download link, "downloaded" (which starts the grace
 * period before deletion), delete now, and the transcript switch. Another user's recording answers 404 like a
 * missing one.
 */
class RecordingService(
    private val db: ServerDatabase,
    private val objects: ObjectStore,
    private val config: RecordingConfig,
    private val clock: Clock,
) {
    /** [userId]'s recordings still on the server, newest first. */
    suspend fun list(userId: UUID): List<RecordingView> =
        db.query {
            Recordings
                .selectAll()
                .where { Recordings.userId eq userId }
                .orderBy(Recordings.createdAt, SortOrder.DESC)
                .limit(MAX_LIST)
                .map(::view)
        }

    /** Recording [id] of [userId], or `null` (what a `recording_ready` push stands for). */
    suspend fun view(
        userId: UUID,
        id: UUID,
    ): RecordingView? = db.query { rowOf(userId, id)?.let(::view) }

    /** A link to [userId]'s recording [id], valid [DOWNLOAD_URL_SECONDS], and the transcript if it is done. */
    suspend fun download(
        userId: UUID,
        id: UUID,
    ): RecordingDownload {
        val row = db.query { rowOf(userId, id) } ?: throw recordingNotFound()
        return RecordingDownload(
            url = objects.presignGet(row[Recordings.objectKey], DOWNLOAD_URL_SECONDS).toString(),
            contentType = CONTENT_TYPE,
            sizeBytes = row[Recordings.sizeBytes],
            expiresInSeconds = DOWNLOAD_URL_SECONDS,
            transcript = row[Recordings.transcript]?.takeIf { row[Recordings.transcriptState] == TRANSCRIPT_DONE },
        )
    }

    /** The app saved [userId]'s recording [id]: it is deleted after the grace period (TC-REC-001). */
    suspend fun downloaded(
        userId: UUID,
        id: UUID,
    ) {
        db.query {
            rowOf(userId, id) ?: throw recordingNotFound()
            // A second confirmation keeps the first time, so retrying never extends the stay.
            Recordings.update({ (Recordings.id eq id) and Recordings.downloadedAt.isNull() }) { it[downloadedAt] = now() }
        }
    }

    /** Deletes [userId]'s recording [id] from the server now. */
    suspend fun delete(
        userId: UUID,
        id: UUID,
    ) {
        val key = db.query { rowOf(userId, id)?.get(Recordings.objectKey) } ?: throw recordingNotFound()
        objects.delete(key)
        db.query { Recordings.deleteWhere { (Recordings.id eq id) and (Recordings.userId eq userId) } }
    }

    /** [userId]'s transcript switch. */
    suspend fun settings(userId: UUID): RecordingSettings = db.query { RecordingSettings(activeUser(userId)[Users.recordingTranscripts]) }

    /** Turns transcripts on or off for [userId]'s future recordings. */
    suspend fun updateSettings(
        userId: UUID,
        settings: RecordingSettings,
    ): RecordingSettings =
        db.query {
            activeUser(userId)
            Users.update({ Users.id eq userId }) { it[recordingTranscripts] = settings.transcripts }
            settings
        }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.rowOf(
        userId: UUID,
        id: UUID,
    ): ResultRow? =
        Recordings
            .selectAll()
            .where { (Recordings.id eq id) and (Recordings.userId eq userId) }
            .singleOrNull()

    private fun view(row: ResultRow): RecordingView {
        val created = row[Recordings.createdAt]
        val latest = created.plusHours(RecordingRetentionJob.MAX_AGE_HOURS)
        val waitsForTranscript = row[Recordings.transcriptState] in setOf(TRANSCRIPT_PENDING, TRANSCRIPT_WORKING)
        val afterDownload =
            row[Recordings.downloadedAt]
                ?.takeUnless { waitsForTranscript }
                ?.plusMinutes(config.downloadGraceMinutes.toLong() + RecordingRetentionJob.INTERVAL_MINUTES)
        val deleteBy = listOfNotNull(latest, afterDownload).min()
        return RecordingView(
            id = row[Recordings.id].toString(),
            createdAt = created.toInstant().toString(),
            durationMs = row[Recordings.durationMs],
            sizeBytes = row[Recordings.sizeBytes],
            transcript = row[Recordings.transcriptState],
            downloaded = row[Recordings.downloadedAt] != null,
            deleteBy = deleteBy.toInstant().toString(),
        )
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    /** Limits. */
    companion object {
        /** Recordings are Opus in OGG (LiveKit track egress). */
        const val CONTENT_TYPE = "audio/ogg"

        /** How long a download link works. */
        const val DOWNLOAD_URL_SECONDS = 15 * 60
        private const val MAX_LIST = 100
    }
}
