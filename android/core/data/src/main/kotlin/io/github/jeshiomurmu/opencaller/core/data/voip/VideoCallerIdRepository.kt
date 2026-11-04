package io.github.jeshiomurmu.opencaller.core.data.voip

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.model.MyVideoCallerId
import io.github.jeshiomurmu.opencaller.core.model.VideoJob
import io.github.jeshiomurmu.opencaller.core.network.MyVideoDto
import io.github.jeshiomurmu.opencaller.core.network.SubmitVideoDto
import io.github.jeshiomurmu.opencaller.core.network.VoipApi
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import io.github.jeshiomurmu.opencaller.core.network.apiCallNoContent
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own video caller ID: upload a recorded clip (at most 10 s, no sound) to a presigned URL, submit it for
 * transcoding on the server, follow its state, delete it; and the clip-download setting.
 */
@Singleton
class VideoCallerIdRepository
    @Inject
    constructor(
        private val api: VoipApi,
        private val preferences: VoipPreferences,
    ) {
        /** True when clips may download on mobile data too (default: Wi-Fi only). */
        val clipsOnMobileData: Flow<Boolean> = preferences.clipsOnMobileData

        /** Allows or forbids clip downloads on mobile data. */
        suspend fun setClipsOnMobileData(allowed: Boolean) = preferences.setClipsOnMobileData(allowed)

        /** The published clip and a new upload's state. */
        suspend fun mine(): AppResult<MyVideoCallerId> = apiCall { api.myVideo() }.map { it.toModel() }

        /** Uploads [clip] (an MP4 the phone recorded) and queues it for transcoding. */
        suspend fun upload(clip: File): AppResult<MyVideoCallerId> {
            if (!clip.isFile || clip.length() !in 1..MAX_UPLOAD_BYTES) return AppResult.Failure(AppError.Validation("invalid_video"))
            return apiCall { api.videoUpload() }.flatMap { upload ->
                if (clip.length() > upload.maxBytes) return@flatMap AppResult.Failure(AppError.Validation("invalid_video"))
                val body = clip.asRequestBody(upload.contentType.toMediaType())
                apiCallNoContent { api.uploadVideo(upload.uploadUrl, upload.contentType, body) }
                    .flatMap { apiCall { api.submitVideo(SubmitVideoDto(upload.key)) } }
                    .map { it.toModel() }
            }
        }

        /** Deletes the published clip and any pending upload. */
        suspend fun delete(): AppResult<Unit> = apiCallNoContent { api.deleteVideo() }

        /** Reports someone's clip as inappropriate. */
        suspend fun report(videoId: String): AppResult<Unit> =
            if (VideoClips.isClipId(videoId)) apiCallNoContent { api.reportVideo(videoId) } else AppResult.Failure(AppError.NotFound)

        private fun MyVideoDto.toModel() =
            MyVideoCallerId(
                videoId = clip?.videoId?.takeIf(VideoClips::isClipId),
                job = VideoJob.parse(job),
                error = error,
                hidden = clip?.hidden == true,
            )

        private companion object {
            const val MAX_UPLOAD_BYTES = 20L * 1024 * 1024
        }
    }
