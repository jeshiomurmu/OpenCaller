package io.github.jeshiomurmu.opencaller.core.network

import kotlinx.serialization.Serializable

/** `GET/PUT /v1/me` response. */
@Serializable
data class MeDto(
    val e164: String,
    val displayName: String? = null,
    val avatarKey: String? = null,
    val avatarUrl: String? = null,
    val visibility: String,
    val incognito: Boolean,
    val badge: String? = null,
    val supporter: Boolean = false,
    val viewerCount: Int = 0,
    val searcherCount: Int = 0,
    val pendingContactRequests: Int = 0,
)

/** `PUT /v1/me` body: the whole editable profile (`null` clears the name or the picture). */
@Serializable
data class UpdateMeDto(
    val displayName: String?,
    val visibility: String,
    val incognito: Boolean,
    val avatarKey: String?,
)

/** `POST /v1/me/avatar` response: where to `PUT` the JPEG, and the key to set afterwards. */
@Serializable
data class AvatarUploadDto(
    val uploadUrl: String,
    val avatarKey: String,
    val contentType: String,
    val maxBytes: Long,
    val expiresInSeconds: Int,
)

/** One visit in [VisitorsDto]. */
@Serializable
data class VisitorDto(
    val name: String? = null,
    val badge: String? = null,
    val at: String,
)

/** `GET /v1/me/viewers` and `/v1/me/searchers` response. */
@Serializable
data class VisitorsDto(
    val kind: String,
    val days: Int,
    val total: Int,
    val items: List<VisitorDto>,
)

/** `GET /v1/profiles/{e164}` response. */
@Serializable
data class PublicProfileDto(
    val e164: String,
    val name: String? = null,
    val avatarUrl: String? = null,
    val badge: String? = null,
)

/** `POST /v1/contact-requests` body. */
@Serializable
data class ContactRequestDto(
    val e164: String,
)

/** `POST /v1/contact-requests` response (always `sent`). */
@Serializable
data class ContactRequestSentDto(
    val status: String,
)

/** A pending request to the signed-in user. */
@Serializable
data class IncomingContactRequestDto(
    val id: Long,
    val requesterE164: String,
    val requesterName: String? = null,
    val createdAt: String,
)

/** `GET /v1/contact-requests` response. */
@Serializable
data class IncomingContactRequestsDto(
    val items: List<IncomingContactRequestDto>,
)

/** `POST /v1/contact-requests/{id}/{approve|deny}` response. */
@Serializable
data class ContactRequestDecisionDto(
    val id: Long,
    val status: String,
)
