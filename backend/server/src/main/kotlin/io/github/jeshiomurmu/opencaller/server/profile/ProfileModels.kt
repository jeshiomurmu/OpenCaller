package io.github.jeshiomurmu.opencaller.server.profile

import kotlinx.serialization.Serializable

/** `GET /v1/me` response: the signed-in user's own profile and what others did with it. */
@Serializable
data class MeResponse(
    val e164: String,
    val displayName: String?,
    val avatarKey: String?,
    val avatarUrl: String?,
    val visibility: String,
    val incognito: Boolean,
    val badge: String?,
    val supporter: Boolean,
    val viewerCount: Int,
    val searcherCount: Int,
    val pendingContactRequests: Int,
)

/**
 * `PUT /v1/me` body: the whole editable profile (send every field; `null` clears the name or the picture).
 * [avatarKey] is the current key or one from `POST /v1/me/avatar` whose upload finished.
 */
@Serializable
data class UpdateMeRequest(
    val displayName: String?,
    val visibility: String,
    val incognito: Boolean,
    val avatarKey: String? = null,
)

/** `POST /v1/me/avatar` response: where to `PUT` the picture, and the key to set with `PUT /v1/me` afterwards. */
@Serializable
data class AvatarUploadResponse(
    val uploadUrl: String,
    val avatarKey: String,
    val contentType: String,
    val maxBytes: Long,
    val expiresInSeconds: Int,
)

/** One entry of "who viewed my profile" or "who searched for me". */
@Serializable
data class VisitorItem(
    val name: String?,
    val badge: String?,
    val at: String,
)

/** `GET /v1/me/viewers` and `GET /v1/me/searchers` response. */
@Serializable
data class VisitorsResponse(
    val kind: String,
    val days: Int,
    val total: Int,
    val items: List<VisitorItem>,
)

/** `GET /v1/profiles/{e164}` response: what this viewer may see of the number's profile. */
@Serializable
data class PublicProfileResponse(
    val e164: String,
    val name: String?,
    val avatarUrl: String?,
    val badge: String?,
)

/** `POST /v1/contact-requests` body. */
@Serializable
data class ContactRequestBody(
    val e164: String,
)

/** `POST /v1/contact-requests` response; the same whether or not the number has a profile. */
@Serializable
data class ContactRequestSent(
    val status: String = "sent",
)

/** A request for the signed-in user's contact details. */
@Serializable
data class IncomingContactRequest(
    val id: Long,
    val requesterE164: String,
    val requesterName: String?,
    val createdAt: String,
)

/** `GET /v1/contact-requests` response: pending requests to the signed-in user, newest first. */
@Serializable
data class IncomingContactRequests(
    val items: List<IncomingContactRequest>,
)

/** `POST /v1/contact-requests/{id}/{approve|deny}` response. */
@Serializable
data class ContactRequestDecision(
    val id: Long,
    val status: String,
)
