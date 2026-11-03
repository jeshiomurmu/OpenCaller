package io.github.jeshiomurmu.opencaller.core.network

import kotlinx.serialization.Serializable

/** `POST /v1/business/claims` body ([method] `sms` or `voice`). */
@Serializable
data class StartClaimDto(
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
data class ClaimCodeDto(
    val code: String,
)

/** `POST /v1/business/claims/{id}/resend` body. */
@Serializable
data class ResendCodeDto(
    val method: String,
)

/** `POST /v1/business/claims/{id}/uploads` body ([kind] `document` or `logo`). */
@Serializable
data class ClaimUploadRequestDto(
    val kind: String,
    val contentType: String,
)

/** Where to upload one file of a claim, and its key for submit. */
@Serializable
data class ClaimUploadDto(
    val uploadUrl: String,
    val key: String,
    val contentType: String,
    val maxBytes: Long,
    val expiresInSeconds: Int,
)

/** `POST /v1/business/claims/{id}/submit` body. */
@Serializable
data class SubmitClaimDto(
    val documentKeys: List<String>,
    val logoKey: String? = null,
)

/** A claim as its claimant sees it. */
@Serializable
data class ClaimDto(
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

/** `GET /v1/business/claims` response. */
@Serializable
data class ClaimsDto(
    val claims: List<ClaimDto>,
)

/** A number of a verified business. */
@Serializable
data class BusinessNumberDto(
    val e164: String,
    val callReason: String? = null,
)

/** `GET /v1/business/{id}`. */
@Serializable
data class BusinessProfileDto(
    val id: String,
    val name: String,
    val category: String? = null,
    val website: String? = null,
    val hours: String? = null,
    val logoUrl: String? = null,
    val verified: Boolean = true,
    val numbers: List<BusinessNumberDto> = emptyList(),
)

/** One entry of `GET /v1/businesses/{countryIso}`; [removed] = the business was revoked. */
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

/** `GET /v1/businesses/{countryIso}` response; pass [syncedAt] as `since` next time. */
@Serializable
data class DirectoryDto(
    val countryIso: String,
    val syncedAt: Long,
    val entries: List<DirectoryEntryDto>,
)
