package io.github.jeshiomurmu.opencaller.server.business

import kotlinx.serialization.Serializable

/**
 * `POST /v1/business/claims` body: the number to claim, the business behind it and how to receive the code
 * proving control of the number ([method] `sms`, or `voice`: a phone call reads the code, for landlines).
 */
@Serializable
data class StartClaimRequest(
    val e164: String,
    val legalName: String,
    val displayName: String,
    val category: String,
    val website: String? = null,
    val hours: String? = null,
    val callReason: String? = null,
    val method: String = "sms",
)

/** `POST /v1/business/claims/{id}/code` body. */
@Serializable
data class ClaimCodeRequest(
    val code: String,
)

/** `POST /v1/business/claims/{id}/resend` body: send a new code, by SMS or by phone call. */
@Serializable
data class ResendCodeRequest(
    val method: String = "sms",
)

/** `POST /v1/business/claims/{id}/uploads` body: [kind] `document` (proof of the business) or `logo`. */
@Serializable
data class ClaimUploadRequest(
    val kind: String,
    val contentType: String,
)

/** Where to `PUT` one file of a claim, and the key to name in `POST …/submit` afterwards. */
@Serializable
data class ClaimUploadResponse(
    val uploadUrl: String,
    val key: String,
    val contentType: String,
    val maxBytes: Long,
    val expiresInSeconds: Int,
)

/** `POST /v1/business/claims/{id}/submit` body: the uploaded documents (at least one) and an optional logo. */
@Serializable
data class SubmitClaimRequest(
    val documentKeys: List<String>,
    val logoKey: String? = null,
)

/**
 * A claim as its claimant sees it. [status]: `verifying` (code sent), `verified` (number proven; upload documents
 * next), `submitted` (waiting for review), `approved`, `rejected` (with [reviewNote]) or `withdrawn`.
 */
@Serializable
data class ClaimResponse(
    val id: String,
    val e164: String,
    val displayName: String,
    val category: String,
    val method: String,
    val status: String,
    val codeExpiresInSeconds: Int? = null,
    val reviewNote: String? = null,
    val businessId: String? = null,
    val createdAt: Long,
)

/** `GET /v1/business/claims` response, newest first. */
@Serializable
data class ClaimsResponse(
    val claims: List<ClaimResponse>,
)

/** One number of a verified business, with the reason it usually calls. */
@Serializable
data class BusinessNumberDto(
    val e164: String,
    val callReason: String? = null,
)

/** `GET /v1/business/{id}`: a verified business's public profile (docs/prd.md F-BZ-02). */
@Serializable
data class BusinessProfileResponse(
    val id: String,
    val name: String,
    val category: String? = null,
    val website: String? = null,
    val hours: String? = null,
    val logoUrl: String? = null,
    val verified: Boolean = true,
    val numbers: List<BusinessNumberDto>,
)

/**
 * One business number in `GET /v1/businesses/{countryIso}?since=`; [removed] tells apps to forget it (the
 * business was revoked or the number taken off it). [logoUrl] is the API path of the logo, if any.
 */
@Serializable
data class DirectoryEntryDto(
    val e164: String,
    val businessId: String,
    val name: String,
    val category: String? = null,
    val callReason: String? = null,
    val logoUrl: String? = null,
    val updatedAt: Long,
    val removed: Boolean = false,
)

/** `GET /v1/businesses/{countryIso}` response. Pass [syncedAt] as `since` next time. */
@Serializable
data class DirectoryResponse(
    val countryIso: String,
    val syncedAt: Long,
    val entries: List<DirectoryEntryDto>,
)
