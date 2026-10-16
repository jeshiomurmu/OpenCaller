package io.github.jeshiomurmu.opencaller.core.telephony.screening

import io.github.jeshiomurmu.opencaller.core.model.BlockRule
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.CallerVerification
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason

/**
 * A call to screen, as read from `Call.Details`.
 *
 * @property handle the number part of the call's `tel:` handle, or `null`/blank when the caller withheld it.
 * @property verification the carrier's STIR/SHAKEN verdict (Android 11+).
 */
data class ScreeningCall(
    val handle: String?,
    val direction: Direction,
    val verification: CallerVerification = CallerVerification.UNKNOWN,
)

/**
 * The engine's answer for a call.
 *
 * @property caller what is known about the caller, for the caller card.
 * @property matchedRule the user rule that decided, for [ScreeningReason.ALLOW_RULE] and [ScreeningReason.RULE].
 * @property latencyMs how long the decision took.
 */
data class ScreeningResult(
    val decision: Decision,
    val reason: ScreeningReason,
    val direction: Direction,
    val caller: CallerInfo,
    val matchedRule: BlockRule? = null,
    val latencyMs: Int = 0,
) {
    /** True for an incoming call that rings or is silenced: one the caller card is shown for. */
    val showsCallerId: Boolean
        get() = direction == Direction.INCOMING && decision != Decision.BLOCK && reason != ScreeningReason.EMERGENCY
}

/**
 * Budgets of the screening engine.
 *
 * @property budgetMillis hard limit for one decision; past it the call rings (docs/architecture.md §4.1).
 * @property regexBudgetMillis limit for one regular-expression rule against one number.
 */
data class ScreeningConfig(
    val budgetMillis: Long = DEFAULT_BUDGET_MILLIS,
    val regexBudgetMillis: Long = DEFAULT_REGEX_BUDGET_MILLIS,
) {
    /** Defaults. */
    companion object {
        /** 1,500 ms: Android waits at most 5 s for an answer, and the phone doesn't ring until it gets one. */
        const val DEFAULT_BUDGET_MILLIS = 1_500L

        /** 100 ms per regular expression; a phone number has at most 16 characters, so real patterns take well under 1 ms. */
        const val DEFAULT_REGEX_BUDGET_MILLIS = 100L
    }
}
