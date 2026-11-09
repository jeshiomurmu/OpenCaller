package io.github.jeshiomurmu.opencaller.feature.family

import androidx.annotation.StringRes
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyRepository
import io.github.jeshiomurmu.opencaller.core.model.FamilyAlertKind
import io.github.jeshiomurmu.opencaller.core.model.FamilySharing

/** What to tell the user about [error]. */
@StringRes
internal fun AppError.message(): Int =
    when (this) {
        AppError.NotFound -> R.string.family_error_code
        AppError.Network, AppError.ServerNotConfigured -> R.string.family_error_network
        is AppError.RateLimited -> R.string.family_error_rate_limited
        is AppError.Validation -> validationMessage(message)
        else -> R.string.family_error_generic
    }

@StringRes
private fun validationMessage(code: String): Int =
    when (code) {
        FamilyRepository.INVALID_CODE -> R.string.family_error_code
        FamilyRepository.CONSENT_REQUIRED -> R.string.family_error_consent
        "already_in_family" -> R.string.family_error_already_in
        "family_full" -> R.string.family_error_full
        "consent_outdated" -> R.string.family_error_update_app
        "too_many_invites" -> R.string.family_error_too_many_invites
        "invalid_request" -> R.string.family_error_label
        else -> R.string.family_error_generic
    }

/** What an alert of this kind means, for the admin. */
@StringRes
internal fun FamilyAlertKind.text(): Int =
    when (this) {
        FamilyAlertKind.OTP_DURING_CALL -> R.string.family_alert_during_call
        FamilyAlertKind.OTP_BURST -> R.string.family_alert_burst
    }

/** A code as shown to people: `ABCD-2345`. */
internal fun displayCode(code: String): String = if (code.length == CODE_LENGTH) "${code.take(HALF)}-${code.drop(HALF)}" else code

/** A name or label as typed: one line, at most [FamilySharing.MAX_LABEL] characters. */
internal fun oneLine(text: String): String = text.replace('\n', ' ').take(FamilySharing.MAX_LABEL)

private const val CODE_LENGTH = 8
private const val HALF = 4
