package io.github.jeshiomurmu.opencaller.core.model

/**
 * Why the screening engine decided what it did (docs/architecture.md §4.1). [code] is what the screening log
 * stores, so it must never change for an existing value.
 */
enum class ScreeningReason(
    val code: String,
) {
    /** The caller is in the user's contacts (step 2). */
    CONTACT("contact"),

    /** A user allow rule matched (step 3). */
    ALLOW_RULE("allow_rule"),

    /** A user block or silence rule matched (step 4). */
    RULE("rule"),

    /** The number is hidden and "Block hidden numbers" is on (step 5). */
    HIDDEN("hidden"),

    /** The number is from another country and "Block foreign numbers" is on (step 6). */
    FOREIGN("foreign"),

    /** The caller is not a contact and "Block numbers not in contacts" is on (step 7). */
    NON_CONTACT("non_contact"),

    /** The number is on the on-device spam list at or above the user's threshold (step 8). */
    SPAM_LIST("spam_list"),

    /** The live lookup's community score is at or above the user's threshold (step 10). */
    NETWORK("network"),

    /** Nothing matched; the call rings with whatever caller information was found. */
    NO_MATCH("no_match"),

    /** The caller's number could not be read as a phone number; the call rings. */
    UNKNOWN_FORMAT("unknown_format"),

    /** Outgoing calls are never blocked (docs/architecture.md §4.2). */
    OUTGOING("outgoing"),

    /** Emergency numbers are never touched. */
    EMERGENCY("emergency"),

    /** The decision took longer than the budget; the call rings (fail open). */
    TIMEOUT("timeout"),

    /** Screening failed; the call rings (fail open). */
    ERROR("error"),
    ;

    /** Lookup by stored [code]. */
    companion object {
        /** The reason stored as [code], or `null` for an unknown code. */
        fun fromCode(code: String): ScreeningReason? = entries.firstOrNull { it.code == code }
    }
}

/** The carrier's STIR/SHAKEN verdict on the caller's number (Android 11+). */
enum class CallerVerification {
    /** Not available: Android 10, an outgoing call, or the carrier doesn't support it. */
    UNKNOWN,

    /** The carrier did not verify the number. */
    NOT_VERIFIED,

    /** The carrier verified the number ("Verified by carrier"). */
    PASSED,

    /** Verification failed: the number may be spoofed ("Possibly spoofed"). */
    FAILED,
}

/**
 * One screening decision, as kept in the screening log (blocked-calls log and debug screen).
 *
 * @property id database id.
 * @property number the caller, or `null` for hidden callers and emergency numbers.
 * @property spamScore the on-device spam list score, if the number was listed.
 * @property latencyMs how long the decision took.
 * @property atMillis when the call was screened, epoch milliseconds.
 */
data class ScreeningEvent(
    val id: Long,
    val number: PhoneNumber?,
    val direction: Direction,
    val decision: Decision,
    val reason: ScreeningReason,
    val spamScore: Float?,
    val verification: CallerVerification,
    val latencyMs: Int,
    val atMillis: Long,
)
