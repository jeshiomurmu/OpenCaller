package io.github.jeshiomurmu.opencaller.feature.premium

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.jeshiomurmu.opencaller.core.model.PaidTier

// Words for the supporter screen's states.

@Composable
internal fun periodText(isoPeriod: String): String =
    when (isoPeriod) {
        "P1M" -> stringResource(R.string.supporter_period_month)
        "P1Y" -> stringResource(R.string.supporter_period_year)
        else -> stringResource(R.string.supporter_period_other, isoPeriod)
    }

internal fun PaidTier.statusRes(): Int =
    when (this) {
        PaidTier.SUPPORTER -> R.string.supporter_status_tier_supporter
        PaidTier.GOLD -> R.string.supporter_status_tier_gold
        PaidTier.FAMILY -> R.string.supporter_status_tier_family
    }

internal fun PaidTier.planRes(): Int =
    when (this) {
        PaidTier.SUPPORTER -> R.string.supporter_plan_supporter
        PaidTier.GOLD -> R.string.supporter_plan_gold
        PaidTier.FAMILY -> R.string.supporter_plan_family
    }

internal fun SupporterNote.textRes(): Int =
    when (this) {
        SupporterNote.THANKS -> R.string.supporter_note_thanks
        SupporterNote.PENDING -> R.string.supporter_note_pending
        SupporterNote.NOTHING_TO_RESTORE -> R.string.supporter_note_nothing
    }

internal fun SupporterProblem.textRes(): Int =
    when (this) {
        SupporterProblem.Offline -> R.string.supporter_problem_offline
        SupporterProblem.NotConnected -> R.string.supporter_problem_not_connected
        SupporterProblem.SignInFirst -> R.string.supporter_problem_sign_in
        SupporterProblem.NoPlans -> R.string.supporter_problem_no_plans
        SupporterProblem.NotAccepted -> R.string.supporter_problem_not_accepted
        SupporterProblem.Unexpected -> R.string.supporter_problem_unexpected
    }
