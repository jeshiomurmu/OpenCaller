package io.github.jeshiomurmu.opencaller.core.network

import kotlinx.serialization.Serializable

/** `PUT /v1/push`: `fcm` with [token], or `webpush` with [endpoint], [p256dh] and [auth] (base64url). */
@Serializable
data class PushRegistrationDto(
    val type: String,
    val token: String? = null,
    val endpoint: String? = null,
    val p256dh: String? = null,
    val auth: String? = null,
)

/**
 * What a push stood for (`GET /v1/notifications/{id}`): `call_invite`, `call_update`, `flash`, `family_alert` or
 * `recording_ready`.
 */
@Serializable
data class NotificationDto(
    val id: String,
    val kind: String,
    val createdAt: String,
    val call: CallInviteDto? = null,
    val flash: FlashDto? = null,
    val familyAlert: FamilyAlertViewDto? = null,
    val recording: RecordingDto? = null,
)

/** An app call as one party sees it; [number], [name] and [videoId] describe the other party. */
@Serializable
data class CallInviteDto(
    val inviteId: String,
    val state: String,
    val role: String,
    val number: String,
    val name: String? = null,
    val videoId: String? = null,
    val ringUntil: String,
)

/** `POST /v1/voip/calls` body. */
@Serializable
data class StartCallDto(
    val to: String,
)

/** A started app call: the caller joins [room] on [url] with [token] at once. */
@Serializable
data class CallSessionDto(
    val inviteId: String,
    val room: String,
    val url: String,
    val token: String,
    val tokenExpiresAt: String,
    val ringUntil: String,
    val reachedDevices: Int = 0,
)

/** `POST /v1/voip/token` body. */
@Serializable
data class RoomTokenRequestDto(
    val inviteId: String,
)

/** A LiveKit room to join. */
@Serializable
data class RoomTokenDto(
    val room: String,
    val url: String,
    val token: String,
    val expiresAt: String,
)

/** `GET`/`PUT /v1/voip/settings`. */
@Serializable
data class VoipSettingsDto(
    val reachable: Boolean,
)

/** `POST /v1/flash` body: one of the predefined [kind]s, no text. */
@Serializable
data class SendFlashDto(
    val to: String,
    val kind: String,
)

/** A sent Flash message. */
@Serializable
data class FlashSentDto(
    val id: String,
    val reachedDevices: Int = 0,
)

/** A received Flash message. */
@Serializable
data class FlashDto(
    val id: String,
    val kind: String,
    val from: String,
    val name: String? = null,
    val sentAt: String,
)

/** Where to `PUT` a new clip (`video/mp4`, at most [maxBytes]) before submitting [key]. */
@Serializable
data class VideoUploadDto(
    val key: String,
    val uploadUrl: String,
    val contentType: String,
    val maxBytes: Long,
    val expiresAt: String,
)

/** `POST /v1/me/video` body. */
@Serializable
data class SubmitVideoDto(
    val key: String,
)

/** The user's clip and upload state ([job]: `none`, `uploading`, `queued`, `processing` or `failed`). */
@Serializable
data class MyVideoDto(
    val clip: VideoClipDto? = null,
    val job: String = "none",
    val error: String? = null,
)

/** A published clip. */
@Serializable
data class VideoClipDto(
    val videoId: String,
    val url: String,
    val bytes: Int? = null,
    val hidden: Boolean = false,
)

/** `POST /v1/videos/links` body. */
@Serializable
data class VideoLinksRequestDto(
    val ids: List<String>,
)

/** Download links for clips. */
@Serializable
data class VideoLinksDto(
    val links: List<VideoLinkDto>,
    val expiresAt: String,
)

/** One clip's download link. */
@Serializable
data class VideoLinkDto(
    val id: String,
    val url: String,
)
