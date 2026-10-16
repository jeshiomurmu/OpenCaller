package io.github.jeshiomurmu.opencaller.core.model

/** How a [BlockRule.pattern] is matched against a number. */
enum class RuleType {
    /** The pattern is one E.164 number. */
    EXACT,

    /** The pattern is an E.164 prefix, e.g. `+1900`. */
    PREFIX,

    /** The pattern is a regular expression over the E.164 string. */
    REGEX,

    /** The pattern is an ISO 3166-1 alpha-2 region code, e.g. `NG`. */
    COUNTRY,
}

/** What happens when a [BlockRule] matches. */
enum class RuleAction {
    /** Reject the call or hide the message. */
    BLOCK,

    /** Let it through without ringing or notifying. */
    SILENCE,

    /** Always let it through; overrides block rules. */
    ALLOW,
}

/** Which traffic a [BlockRule] applies to. */
enum class RuleTarget {
    /** Calls only. */
    CALLS,

    /** SMS only. */
    SMS,

    /** Calls and SMS. */
    BOTH,
}

/**
 * A user-defined blocking rule.
 *
 * @property id database id; `0` for a rule not saved yet.
 * @property createdAtMillis creation time in epoch milliseconds.
 */
data class BlockRule(
    val id: Long,
    val type: RuleType,
    val pattern: String,
    val action: RuleAction,
    val target: RuleTarget,
    val createdAtMillis: Long,
    val enabled: Boolean,
)
