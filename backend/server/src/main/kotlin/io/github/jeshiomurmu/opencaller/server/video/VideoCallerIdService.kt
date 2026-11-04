package io.github.jeshiomurmu.opencaller.server.video

import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.VideoCallerIds
import io.github.jeshiomurmu.opencaller.server.db.VideoReports
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStore
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Video caller ID (docs/prd.md F-ID-06): a user records a clip of at most ten seconds and uploads it with a
 * pre-signed URL; the transcoder worker turns it into a small silent MP4 under a fresh random id. The id rides in
 * the user's bucket entries (same visibility rule as their name) and in call invites; phones fetch clips by id
 * with [links], always every clip of a bucket so the server never learns which caller rang (ADR 0007). Three
 * reports from different users hide a clip until it is reviewed.
 */
class VideoCallerIdService(
    private val db: ServerDatabase,
    private val objects: ObjectStore,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    /** A pre-signed upload for a new clip of [userId]; the current clip stays visible until the new one is ready. */
    suspend fun upload(userId: UUID): VideoUpload {
        enforce(rateLimiter.hit(UPLOADS_PER_USER, userId.toString()), "Too many video uploads")
        val key = "$UPLOAD_PREFIX$userId/${UUID.randomUUID()}.mp4"
        val now = OffsetDateTime.now(clock)
        db.query {
            VideoCallerIds.upsert(
                onUpdateExclude = listOf(VideoCallerIds.videoId, VideoCallerIds.videoKey, VideoCallerIds.videoBytes, VideoCallerIds.hidden),
            ) {
                it[VideoCallerIds.userId] = userId
                it[uploadKey] = key
                it[jobState] = UPLOADING
                it[jobError] = null
                it[updatedAt] = now
            }
        }
        val url = objects.presignPut(key, CONTENT_TYPE, UPLOAD_URL_SECONDS)
        return VideoUpload(
            key,
            url.toString(),
            CONTENT_TYPE,
            MAX_UPLOAD_BYTES,
            now.plusSeconds(UPLOAD_URL_SECONDS.toLong()).toInstant().toString(),
        )
    }

    /** Queues [request]'s upload for transcoding once it is in the store, of the right type and size. */
    suspend fun submit(
        userId: UUID,
        request: SubmitVideoRequest,
    ): MyVideo {
        val row = db.query { rowOf(userId) }
        requireValid(row != null && row[VideoCallerIds.uploadKey] == request.key && row[VideoCallerIds.jobState] == UPLOADING) {
            invalidVideo("Ask for an upload URL first and submit its key.")
        }
        val info = objects.head(request.key) ?: throw invalidVideo("Upload the clip to uploadUrl before submitting it.")
        requireValid(info.size in 1..MAX_UPLOAD_BYTES && info.contentType == CONTENT_TYPE) {
            invalidVideo("The clip must be an MP4 of at most $MAX_UPLOAD_BYTES bytes.")
        }
        db.query {
            VideoCallerIds.update({ VideoCallerIds.userId eq userId }) {
                it[jobState] = QUEUED
                it[updatedAt] = OffsetDateTime.now(clock)
            }
        }
        return mine(userId)
    }

    /** [userId]'s clip and upload state. */
    suspend fun mine(userId: UUID): MyVideo {
        val row = db.query { rowOf(userId) } ?: return MyVideo()
        val clip =
            row[VideoCallerIds.videoId]?.let { id ->
                VideoClip(
                    videoId = id.toString(),
                    url = objects.presignGet(checkNotNull(row[VideoCallerIds.videoKey]), DOWNLOAD_URL_SECONDS).toString(),
                    bytes = row[VideoCallerIds.videoBytes],
                    hidden = row[VideoCallerIds.hidden],
                )
            }
        return MyVideo(clip, row[VideoCallerIds.jobState], row[VideoCallerIds.jobError])
    }

    /** Deletes [userId]'s clip and any pending upload. */
    suspend fun delete(userId: UUID) {
        val row = db.query { rowOf(userId)?.also { VideoCallerIds.deleteWhere { VideoCallerIds.userId eq userId } } } ?: return
        listOfNotNull(row[VideoCallerIds.videoKey], row[VideoCallerIds.uploadKey]).forEach { objects.delete(it) }
    }

    /** Download links for the ready, visible clips among [request]'s ids; rate-limited per [client] address. */
    suspend fun links(
        request: VideoLinksRequest,
        client: String,
    ): VideoLinks {
        requireValid(request.ids.size in 1..MAX_LINKS) { invalidVideo("Ask for 1 to $MAX_LINKS clips at a time.") }
        val ids =
            request.ids
                .filter(UUID_PATTERN::matches)
                .map(UUID::fromString)
                .distinct()
        enforce(rateLimiter.hit(LINKS_PER_CLIENT, client), "Too many video requests")
        val rows =
            db.query {
                VideoCallerIds
                    .selectAll()
                    .where { (VideoCallerIds.videoId inList ids) and (VideoCallerIds.hidden eq false) }
                    .toList()
            }
        val links =
            rows.mapNotNull { row ->
                val id = row[VideoCallerIds.videoId] ?: return@mapNotNull null
                val key = row[VideoCallerIds.videoKey] ?: return@mapNotNull null
                VideoLink(id.toString(), objects.presignGet(key, DOWNLOAD_URL_SECONDS).toString())
            }
        return VideoLinks(
            links,
            OffsetDateTime
                .now(clock)
                .plusSeconds(DOWNLOAD_URL_SECONDS.toLong())
                .toInstant()
                .toString(),
        )
    }

    /** Records [reporterId]'s report of clip [videoId]; the third reporter hides it. */
    suspend fun report(
        reporterId: UUID,
        videoId: UUID,
    ) {
        enforce(rateLimiter.hit(REPORTS_PER_USER, reporterId.toString()), "Too many reports")
        db.query {
            val owner =
                VideoCallerIds.selectAll().where { VideoCallerIds.videoId eq videoId }.firstOrNull()
                    ?: throw ApiProblem(HttpStatusCode.NotFound, "video_not_found", "No such clip")
            requireValid(owner[VideoCallerIds.userId] != reporterId) { invalidVideo("You can't report your own clip.") }
            VideoReports.insertIgnore {
                it[VideoReports.videoId] = videoId
                it[VideoReports.reporterId] = reporterId
            }
            val reports = VideoReports.selectAll().where { VideoReports.videoId eq videoId }.count()
            if (reports >= HIDE_AFTER_REPORTS) VideoCallerIds.update({ VideoCallerIds.videoId eq videoId }) { it[hidden] = true }
        }
    }

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.rowOf(userId: UUID): ResultRow? =
        VideoCallerIds.selectAll().where { VideoCallerIds.userId eq userId }.firstOrNull()

    private fun invalidVideo(detail: String) = ApiProblem(HttpStatusCode.BadRequest, "invalid_video", "Invalid video caller ID", detail)

    /** Limits and states shared with the transcoder. */
    companion object {
        /** Uploads arrive under this prefix; finished clips go under [CLIP_PREFIX]. */
        const val UPLOAD_PREFIX = "video-uploads/"

        /** Finished clips. */
        const val CLIP_PREFIX = "videos/"

        /** The only accepted and produced type. */
        const val CONTENT_TYPE = "video/mp4"

        /** A camera clip of ten seconds is a few MB; anything much larger isn't one. */
        const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024
        internal const val UPLOADING = "uploading"
        internal const val QUEUED = "queued"
        private const val UPLOAD_URL_SECONDS = 600
        private const val DOWNLOAD_URL_SECONDS = 900
        private const val MAX_LINKS = 64
        private const val HIDE_AFTER_REPORTS = 3L
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val UPLOADS_PER_USER = RateLimit("video_upload_user", maxHits = 10, window = 1.days)
        private val LINKS_PER_CLIENT = RateLimit("video_links_ip", maxHits = 240, window = 1.hours)
        private val REPORTS_PER_USER = RateLimit("video_report_user", maxHits = 20, window = 1.days)
    }
}
