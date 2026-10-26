package io.github.jeshiomurmu.opencaller.core.network

import kotlinx.serialization.Serializable

// Request and response bodies of the OpenCaller server (backend/server/src/main/resources/openapi.yaml).

/** `POST /v1/auth/start` and `POST /v1/unlist/start` body. */
@Serializable
data class StartRequest(
    val e164: String,
)

/** `POST /v1/auth/start` response. */
@Serializable
data class StartResponse(
    val expiresInSeconds: Long,
)

/** `POST /v1/auth/verify` body. */
@Serializable
data class VerifyRequest(
    val e164: String,
    val code: String,
    val flavor: String,
)

/** `POST /v1/auth/refresh` body. */
@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

/** Tokens returned by verify and refresh. */
@Serializable
data class TokenResponse(
    val accessToken: String,
    val accessTokenExpiresIn: Long,
    val refreshToken: String,
    val refreshTokenExpiresIn: Long,
    val tokenType: String = "Bearer",
)

/** One report in `POST /v1/reports`. */
@Serializable
data class ReportItemDto(
    val e164: String,
    val label: String,
    val category: String? = null,
    val comment: String? = null,
)

/** `POST /v1/reports` body (1–50 reports). */
@Serializable
data class ReportBatchDto(
    val reports: List<ReportItemDto>,
)

/** `POST /v1/reports` response. */
@Serializable
data class ReportBatchResponse(
    val accepted: Int,
)

/** A verified business in lookup results. */
@Serializable
data class BusinessInfoDto(
    val name: String,
    val category: String? = null,
    val verified: Boolean = true,
    val callReason: String? = null,
)

/** What the server may say about a number. */
@Serializable
data class NumberInfoDto(
    val name: String? = null,
    val spamScore: Double? = null,
    val label: String? = null,
    val category: String? = null,
    val reportCount: Int = 0,
    val notSpamCount: Int = 0,
    val lineType: String? = null,
    val business: BusinessInfoDto? = null,
)

/** One bucket entry: base64 SHA-256 of the E.164 number, and its info. */
@Serializable
data class BucketEntryDto(
    val hash: String,
    val info: NumberInfoDto,
)

/** `GET /v1/lookup/buckets/{cc}/{bucketId}` response. */
@Serializable
data class BucketResponse(
    val countryCode: Int,
    val bucket: Int,
    val bits: Int,
    val entries: List<BucketEntryDto>,
)

/** `GET /v1/numbers/{e164}` response. */
@Serializable
data class SearchResponse(
    val e164: String,
    val info: NumberInfoDto,
)

/** A list file in the manifest; [baseVersion] is set for deltas. */
@Serializable
data class ListFileRefDto(
    val version: Long,
    val baseVersion: Long? = null,
    val url: String,
    val sha256: String,
    val createdAt: String,
)

/** `GET /v1/lists/{countryIso}/manifest` response. */
@Serializable
data class ListManifestDto(
    val countryIso: String,
    val format: Int,
    val version: Long,
    val full: ListFileRefDto,
    val deltas: List<ListFileRefDto>,
)

/** `POST /v1/disputes` body. */
@Serializable
data class DisputeRequest(
    val e164: String,
    val reason: String,
)

/** `POST /v1/disputes` response. */
@Serializable
data class DisputeResponse(
    val id: Long,
    val status: String,
)

/** RFC 9457 problem details as the server sends them. */
@Serializable
data class ProblemDto(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val code: String? = null,
)
