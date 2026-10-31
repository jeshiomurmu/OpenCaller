package io.github.jeshiomurmu.opencaller.core.model

/**
 * A risk signal found in a received message, stored as a bit in `MessageMeta.riskFlags` (docs/architecture.md §4.4
 * steps 5 and 6). A risk's bit is `1 shl ordinal` and is written to the database: add new risks at the end only,
 * never reorder or remove one (`MessageRiskTest` pins the values).
 */
enum class MessageRisk {
    /** A link whose host imitates a known brand (`paypa1-secure…`, `sbi-kyc…`). */
    LOOKALIKE_LINK,

    /** A link whose host mixes Latin with letters of another script (a Cyrillic `о` in `nоrthpeak`). */
    MIXED_SCRIPT_LINK,

    /** A link through a URL shortener, which hides where it leads. */
    SHORTENED_LINK,

    /** A link on a top-level domain or with words in its host that phishing sites favour (`.top`, `verify-login`). */
    SUSPICIOUS_LINK,

    /** A link whose host is on OpenCaller's known-bad list (hash-prefix match). */
    LISTED_LINK,

    /** A one-time code arrived during, or within 2 minutes after, a call with someone not in the contacts. */
    OTP_DURING_CALL,

    /** Several one-time codes from different senders in a short time (F-PR-12). */
    OTP_BURST,

    /** A one-time code the user doesn't seem to have asked for. */
    UNREQUESTED_OTP,
    ;

    /** This risk's bit in `MessageMeta.riskFlags`. */
    val bit: Int get() = 1 shl ordinal

    /** Conversions between sets of risks and the stored bitmask. */
    companion object {
        /** The bitmask of [risks]. */
        fun toBits(risks: Collection<MessageRisk>): Int = risks.fold(0) { bits, risk -> bits or risk.bit }

        /** The risks set in [bits]; unknown bits are ignored. */
        fun fromBits(bits: Int): Set<MessageRisk> = entries.filterTo(mutableSetOf()) { bits and it.bit != 0 }

        /** Risks that come from a link in the message. */
        val LINK_RISKS: Set<MessageRisk> = setOf(LOOKALIKE_LINK, MIXED_SCRIPT_LINK, SHORTENED_LINK, SUSPICIOUS_LINK, LISTED_LINK)
    }
}
