package io.github.jeshiomurmu.opencaller.server.business

import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.isPlainText
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.ktor.http.HttpStatusCode

/** How a claimant receives the code that proves control of the number. */
enum class ClaimMethod(
    val value: String,
) {
    SMS("sms"),
    VOICE("voice"),
    ;

    /** Parsing. */
    companion object {
        /** The method named [value], or 400 `invalid_request`. */
        fun of(value: String): ClaimMethod = entries.firstOrNull { it.value == value } ?: throw invalidClaim("method must be sms or voice.")
    }
}

/** Where a claim stands; see [ClaimResponse.status]. */
enum class ClaimStatus(
    val value: String,
) {
    VERIFYING("verifying"),
    VERIFIED("verified"),
    SUBMITTED("submitted"),
    APPROVED("approved"),
    REJECTED("rejected"),
    WITHDRAWN("withdrawn"),
    ;

    /** `true` while the claim is in progress (one open claim per number). */
    val isOpen: Boolean get() = this == VERIFYING || this == VERIFIED || this == SUBMITTED

    /** Parsing. */
    companion object {
        /** The status stored as [value]. */
        fun of(value: String): ClaimStatus = entries.first { it.value == value }
    }
}

/** A file a claimant uploads: what it may be and how large. */
enum class UploadKind(
    val value: String,
    val keyPrefix: String,
    val contentTypes: Map<String, String>,
    val maxBytes: Long,
) {
    /** Proof of the business (registration certificate, utility bill on the number, …). */
    DOCUMENT("document", "doc-", mapOf("application/pdf" to "pdf", "image/jpeg" to "jpg", "image/png" to "png"), MAX_DOCUMENT_BYTES),

    /** The business's logo, shown on caller cards once approved. */
    LOGO("logo", "logo-", mapOf("image/png" to "png", "image/jpeg" to "jpg"), MAX_LOGO_BYTES),
    ;

    /** Parsing. */
    companion object {
        /** The kind named [value], or 400 `invalid_request`. */
        fun of(value: String): UploadKind =
            entries.firstOrNull { it.value == value } ?: throw invalidClaim("kind must be document or logo.")
    }
}

private const val MAX_DOCUMENT_BYTES = 10_000_000L
private const val MAX_LOGO_BYTES = 200_000L

/** Field rules for a claim (docs/rules.md §7: length limits and enum whitelists on every input). */
object ClaimRules {
    /** Business categories apps know how to show. */
    val CATEGORIES =
        setOf(
            "bank",
            "finance",
            "insurance",
            "telecom",
            "utility",
            "government",
            "healthcare",
            "education",
            "retail",
            "delivery",
            "travel",
            "transport",
            "food",
            "services",
            "other",
        )

    /** Longest legal or display name. */
    const val MAX_NAME = 100

    /** Longest website, opening hours or call reason. */
    const val MAX_SHORT_TEXT = 200

    /** Documents per claim. */
    const val MAX_DOCUMENTS = 5

    /** Upload URLs one claim may ask for (so a claim can't fill the store). */
    const val MAX_UPLOADS = 20

    /** Checks the profile fields of [request]; throws 400 `invalid_request` naming the first bad field. */
    fun check(request: StartClaimRequest) {
        requireValid(oneLine(request.legalName, MAX_NAME)) { invalidClaim("legalName must be 1 to $MAX_NAME characters on one line.") }
        requireValid(oneLine(request.displayName, MAX_NAME)) { invalidClaim("displayName must be 1 to $MAX_NAME characters on one line.") }
        requireValid(request.category in CATEGORIES) { invalidClaim("category must be one of ${CATEGORIES.sorted().joinToString()}.") }
        requireValid(request.website == null || isWebsite(request.website)) {
            invalidClaim("website must be an https:// address of at most $MAX_SHORT_TEXT characters.")
        }
        requireValid(request.hours == null || isPlainText(request.hours, MAX_SHORT_TEXT)) {
            invalidClaim("hours must be at most $MAX_SHORT_TEXT characters.")
        }
        requireValid(request.callReason == null || oneLine(request.callReason, MAX_SHORT_TEXT)) {
            invalidClaim("callReason must be at most $MAX_SHORT_TEXT characters on one line.")
        }
    }

    private fun oneLine(
        text: String,
        max: Int,
    ) = text.trim() == text && isPlainText(text, max) && '\n' !in text

    private fun isWebsite(url: String) =
        url.length <= MAX_SHORT_TEXT && url.startsWith("https://") && url.length > "https://".length &&
            url.none { it.isWhitespace() || it.isISOControl() }
}

/** 400 `invalid_request` for a claim. */
internal fun invalidClaim(detail: String) = ApiProblem(HttpStatusCode.BadRequest, "invalid_request", "Invalid business claim", detail)

/** 404 for a claim the caller doesn't own or that doesn't exist (the same answer for both). */
internal fun claimNotFound() = ApiProblem(HttpStatusCode.NotFound, "claim_not_found", "No such business claim")

/** 409 `claim_state`: the claim isn't at the step this request belongs to. */
internal fun wrongStep(detail: String) = ApiProblem(HttpStatusCode.Conflict, "claim_state", "The claim is not at this step", detail)
