package io.github.jeshiomurmu.opencaller.server.video

import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.VideoCallerIds
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStore
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStoreException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/** A claimed transcode job: the user and their uploaded clip. */
data class TranscodeJob(
    val userId: UUID,
    val uploadKey: String,
)

/** What one [TranscodeWorker.runOnce] did. */
enum class TranscodeOutcome { IDLE, PUBLISHED, FAILED }

/**
 * The transcoder worker (its own container, `opencaller-server transcoder`): claims one queued upload at a time
 * (`FOR UPDATE SKIP LOCKED`, so several workers never take the same job; a job claimed more than ten minutes ago
 * by a worker that died is taken again), transcodes it, publishes it under a fresh random id and deletes the
 * upload. The old clip stays visible until the new one is published, then it is deleted too.
 */
class TranscodeWorker(
    private val db: ServerDatabase,
    private val objects: ObjectStore,
    private val transcoder: Transcoder,
    private val clock: Clock,
) {
    /** Processes the next queued job, if any. */
    suspend fun runOnce(): TranscodeOutcome {
        val job = claim() ?: return TranscodeOutcome.IDLE
        return try {
            publish(job)
            TranscodeOutcome.PUBLISHED
        } catch (e: TranscodeException) {
            log.warn("Clip for {} not transcoded: {}", job.userId, e.message)
            fail(job, "The clip could not be processed. Record it again.")
            TranscodeOutcome.FAILED
        } catch (e: ObjectStoreException) {
            log.warn("Clip for {} not transcoded: object store", job.userId, e)
            fail(job, "The clip could not be stored. Try again later.")
            TranscodeOutcome.FAILED
        }
    }

    private suspend fun claim(): TranscodeJob? =
        db.query {
            // UPDATE … RETURNING yields rows, so it runs as a query.
            exec(CLAIM_SQL, explicitStatementType = StatementType.SELECT) { rs ->
                if (rs.next()) TranscodeJob(UUID.fromString(rs.getString(1)), rs.getString(2)) else null
            }
        }

    private suspend fun publish(job: TranscodeJob) {
        val upload = objects.get(job.uploadKey) ?: throw TranscodeException("the upload is gone")
        val dir = Files.createTempDirectory("clip")
        val input = dir.resolve("in.mp4")
        val output = dir.resolve("out.mp4")
        val clip =
            try {
                Files.write(input, upload)
                transcoder.transcode(input, output)
                Files.readAllBytes(output)
            } finally {
                Files.deleteIfExists(input)
                Files.deleteIfExists(output)
                Files.deleteIfExists(dir)
            }
        if (clip.isEmpty() || clip.size > MAX_CLIP_BYTES) throw TranscodeException("the transcoded clip has ${clip.size} bytes")
        val videoId = UUID.randomUUID()
        val key = "${VideoCallerIdService.CLIP_PREFIX}$videoId.mp4"
        objects.put(key, clip, VideoCallerIdService.CONTENT_TYPE)
        val previous =
            db.query {
                val old =
                    VideoCallerIds.selectAll().where { VideoCallerIds.userId eq job.userId }.firstOrNull()?.get(
                        VideoCallerIds.videoKey,
                    )
                val updated =
                    VideoCallerIds.update({ (VideoCallerIds.userId eq job.userId) and (VideoCallerIds.uploadKey eq job.uploadKey) }) {
                        it[VideoCallerIds.videoId] = videoId
                        it[videoKey] = key
                        it[videoBytes] = clip.size
                        it[uploadKey] = null
                        it[jobState] = NONE
                        it[jobError] = null
                        it[claimedAt] = null
                        it[hidden] = false
                        it[updatedAt] = OffsetDateTime.now(clock)
                    }
                // The user deleted the clip or started another upload meanwhile: this result is no longer wanted.
                if (updated == 1) old else key
            }
        listOfNotNull(previous, job.uploadKey).forEach { objects.delete(it) }
    }

    private suspend fun fail(
        job: TranscodeJob,
        reason: String,
    ) {
        db.query {
            VideoCallerIds.update({ (VideoCallerIds.userId eq job.userId) and (VideoCallerIds.uploadKey eq job.uploadKey) }) {
                it[uploadKey] = null
                it[jobState] = FAILED
                it[jobError] = reason
                it[claimedAt] = null
                it[updatedAt] = OffsetDateTime.now(clock)
            }
        }
        objects.delete(job.uploadKey)
    }

    private companion object {
        const val NONE = "none"
        const val FAILED = "failed"

        // A ten-second clip at 480 px and CRF 30 is far below this.
        const val MAX_CLIP_BYTES = 4 * 1024 * 1024
        val log = LoggerFactory.getLogger(TranscodeWorker::class.java)
        val CLAIM_SQL =
            """
            UPDATE video_caller_ids SET job_state = 'processing', claimed_at = now(), updated_at = now()
            WHERE user_id = (
              SELECT user_id FROM video_caller_ids
              WHERE upload_key IS NOT NULL AND (job_state = 'queued'
                OR (job_state = 'processing' AND claimed_at < now() - interval '10 minutes'))
              ORDER BY updated_at FOR UPDATE SKIP LOCKED LIMIT 1)
            RETURNING user_id, upload_key
            """.trimIndent()
    }
}
