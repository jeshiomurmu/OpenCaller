package io.github.jeshiomurmu.opencaller.server.video

import kotlinx.serialization.Serializable

/** Where to `PUT` a new clip (MP4, ≤ [maxBytes], `Content-Type: video/mp4`) before [expiresAt], then submit [key]. */
@Serializable
data class VideoUpload(
    val key: String,
    val uploadUrl: String,
    val contentType: String,
    val maxBytes: Long,
    val expiresAt: String,
)

/** `POST /v1/me/video`: the uploaded clip's [key], to transcode. */
@Serializable
data class SubmitVideoRequest(
    val key: String,
)

/** The user's clip as others see it. */
@Serializable
data class VideoClip(
    val videoId: String,
    val url: String,
    val bytes: Int?,
    val hidden: Boolean,
)

/**
 * `GET /v1/me/video`: the current [clip] (if any) and a new upload's [job] (`none`, `uploading`, `queued`,
 * `processing` or `failed`, with [error]).
 */
@Serializable
data class MyVideo(
    val clip: VideoClip? = null,
    val job: String = "none",
    val error: String? = null,
)

/** `POST /v1/videos/links`: download links for clips [ids] (from bucket entries or a call invite). */
@Serializable
data class VideoLinksRequest(
    val ids: List<String>,
)

/** One clip's download link. */
@Serializable
data class VideoLink(
    val id: String,
    val url: String,
)

/** Links valid until [expiresAt]; unknown, hidden or not-ready ids are left out. */
@Serializable
data class VideoLinks(
    val links: List<VideoLink>,
    val expiresAt: String,
)
