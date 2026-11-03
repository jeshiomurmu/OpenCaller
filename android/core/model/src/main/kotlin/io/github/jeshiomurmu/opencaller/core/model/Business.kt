package io.github.jeshiomurmu.opencaller.core.model

/** Business categories the server accepts on a claim (docs/prd.md F-BZ-01); [wire] is the API value. */
enum class BusinessCategory(
    val wire: String,
) {
    BANK("bank"),
    FINANCE("finance"),
    INSURANCE("insurance"),
    TELECOM("telecom"),
    UTILITY("utility"),
    GOVERNMENT("government"),
    HEALTHCARE("healthcare"),
    EDUCATION("education"),
    RETAIL("retail"),
    DELIVERY("delivery"),
    TRAVEL("travel"),
    TRANSPORT("transport"),
    FOOD("food"),
    SERVICES("services"),
    OTHER("other"),
    ;

    /** Parsing. */
    companion object {
        /** The category sent as [wire], or `null` for one this app version doesn't know. */
        fun parse(wire: String?): BusinessCategory? = entries.firstOrNull { it.wire == wire }
    }
}

/** How the code proving control of a claimed number arrives: a text message or a phone call reading it out. */
enum class ClaimMethod(
    val wire: String,
) {
    SMS("sms"),
    VOICE("voice"),
    ;

    /** Parsing. */
    companion object {
        /** The method sent as [wire]; anything unknown counts as [SMS]. */
        fun parse(wire: String): ClaimMethod = entries.firstOrNull { it.wire == wire } ?: SMS
    }
}

/** Where a business claim stands. */
enum class ClaimStatus(
    val wire: String,
) {
    /** The code was sent to the claimed number. */
    VERIFYING("verifying"),

    /** The number is proven; documents come next. */
    VERIFIED("verified"),

    /** Waiting for review. */
    SUBMITTED("submitted"),

    /** Approved: the business shows as verified. */
    APPROVED("approved"),

    /** Rejected, with the reviewer's note. */
    REJECTED("rejected"),

    /** Withdrawn by the claimant. */
    WITHDRAWN("withdrawn"),
    ;

    /** `true` while the claim is in progress. */
    val isOpen: Boolean get() = this == VERIFYING || this == VERIFIED || this == SUBMITTED

    /** Parsing. */
    companion object {
        /** The status sent as [wire], or `null` for one this app version doesn't know. */
        fun parse(wire: String): ClaimStatus? = entries.firstOrNull { it.wire == wire }
    }
}

/** What the claimant enters to claim [number] for a business. */
data class ClaimForm(
    val number: PhoneNumber,
    val legalName: String,
    val displayName: String,
    val category: BusinessCategory,
    val website: String?,
    val hours: String?,
    val callReason: String?,
    val method: ClaimMethod,
)

/** One of the user's business claims, as the server reports it. [codeExpiresInSeconds] while a code is pending. */
data class BusinessClaim(
    val id: String,
    val number: PhoneNumber,
    val displayName: String,
    val category: BusinessCategory?,
    val method: ClaimMethod,
    val status: ClaimStatus,
    val codeExpiresInSeconds: Int?,
    val reviewNote: String?,
    val businessId: String?,
    val createdAtMillis: Long,
)

/** A file the claimant sends with a claim. */
enum class ClaimFileKind(
    val wire: String,
) {
    /** Proof of the business (PDF, JPEG or PNG, at most [MAX_DOCUMENT_BYTES]). */
    DOCUMENT("document"),

    /** The logo shown on caller cards (PNG or JPEG, at most [MAX_LOGO_BYTES]). */
    LOGO("logo"),
    ;

    /** Limits the server applies. */
    companion object {
        /** Largest document the server accepts. */
        const val MAX_DOCUMENT_BYTES = 10_000_000L

        /** Largest logo the server accepts. */
        const val MAX_LOGO_BYTES = 200_000L

        /** Documents per claim. */
        const val MAX_DOCUMENTS = 5
    }
}

/** True when [id] is a verified business's profile id (a UUID), not just a "verified" marker. */
fun isBusinessProfileId(id: String?): Boolean = id != null && BUSINESS_ID.matches(id)

private val BUSINESS_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** A verified business's public profile (docs/prd.md F-BZ-02). [logoUrl] is an API path. */
data class BusinessProfile(
    val id: String,
    val name: String,
    val category: BusinessCategory?,
    val website: String?,
    val hours: String?,
    val logoUrl: String?,
    val numbers: List<BusinessNumber>,
)

/** One number of a verified business and why it usually calls. */
data class BusinessNumber(
    val number: PhoneNumber,
    val callReason: String?,
)

/**
 * A verified business number kept on the phone (the directory of the phone's country), so it is known before any
 * lookup and offline. [logoPath] is the logo's file on the phone once downloaded.
 */
data class VerifiedBusiness(
    val number: PhoneNumber,
    val businessId: String,
    val name: String,
    val category: BusinessCategory?,
    val callReason: String?,
    val logoPath: String?,
)
