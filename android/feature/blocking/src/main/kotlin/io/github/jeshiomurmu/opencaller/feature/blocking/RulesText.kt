package io.github.jeshiomurmu.opencaller.feature.blocking

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.telephony.screening.RuleProblem

/** What the editor says about a rejected pattern. */
internal fun RuleProblem.messageRes(): Int =
    when (this) {
        RuleProblem.EMPTY -> R.string.rules_problem_empty
        RuleProblem.TOO_LONG -> R.string.rules_problem_too_long
        RuleProblem.NOT_A_NUMBER -> R.string.rules_problem_not_a_number
        RuleProblem.NOT_A_PREFIX -> R.string.rules_problem_not_a_prefix
        RuleProblem.UNKNOWN_COUNTRY -> R.string.rules_problem_unknown_country
        RuleProblem.INVALID_REGEX -> R.string.rules_problem_invalid_regex
        RuleProblem.SLOW_REGEX -> R.string.rules_problem_slow_regex
    }

/** "Test a number" in plain words: what the next call would do and why. */
@Composable
internal fun testOutcomeText(outcome: TestOutcome): String =
    when (outcome) {
        TestOutcome.NotANumber -> stringResource(R.string.test_not_a_number)
        is TestOutcome.Result -> stringResource(outcome.messageRes(), outcome.display, outcome.rulePattern.orEmpty())
    }

private fun TestOutcome.Result.messageRes(): Int =
    when (reason) {
        ScreeningReason.CONTACT -> {
            R.string.test_contact
        }

        ScreeningReason.ALLOW_RULE -> {
            R.string.test_allow_rule
        }

        ScreeningReason.RULE -> {
            if (decision == Decision.SILENCE) R.string.test_silence_rule else R.string.test_block_rule
        }

        ScreeningReason.FOREIGN -> {
            R.string.test_foreign
        }

        ScreeningReason.NON_CONTACT -> {
            R.string.test_non_contact
        }

        ScreeningReason.SPAM_LIST, ScreeningReason.NETWORK -> {
            spamMessageRes()
        }

        ScreeningReason.EMERGENCY -> {
            R.string.test_emergency
        }

        ScreeningReason.TIMEOUT, ScreeningReason.ERROR -> {
            R.string.test_failed_open
        }

        ScreeningReason.NO_MATCH, ScreeningReason.HIDDEN, ScreeningReason.UNKNOWN_FORMAT, ScreeningReason.OUTGOING -> {
            if (risk == RiskLevel.SUSPICIOUS) R.string.test_suspicious else R.string.test_rings
        }
    }

private fun TestOutcome.Result.spamMessageRes(): Int {
    val silenced = decision == Decision.SILENCE
    return when {
        reason == ScreeningReason.NETWORK && silenced -> R.string.test_network_silenced
        reason == ScreeningReason.NETWORK -> R.string.test_network_blocked
        silenced -> R.string.test_spam_silenced
        else -> R.string.test_spam_blocked
    }
}

/** Why a call is in the blocked log, in a few words. */
internal fun ScreeningReason.logReasonRes(): Int? =
    when (this) {
        ScreeningReason.RULE -> R.string.log_reason_rule
        ScreeningReason.HIDDEN -> R.string.log_reason_hidden
        ScreeningReason.FOREIGN -> R.string.log_reason_foreign
        ScreeningReason.NON_CONTACT -> R.string.log_reason_non_contact
        ScreeningReason.SPAM_LIST -> R.string.log_reason_spam
        ScreeningReason.NETWORK -> R.string.log_reason_network
        else -> null
    }
