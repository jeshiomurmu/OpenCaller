package io.github.jeshiomurmu.opencaller.feature.social

import androidx.annotation.StringRes
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.model.FlashKind

/** The text of each Flash message in the reader's language (the server only knows the kind). */
@StringRes
fun FlashKind.label(): Int =
    when (this) {
        FlashKind.CALL_ME -> R.string.flash_call_me
        FlashKind.ON_MY_WAY -> R.string.flash_on_my_way
        FlashKind.RUNNING_LATE -> R.string.flash_running_late
        FlashKind.CANT_TALK -> R.string.flash_cant_talk
        FlashKind.ARRIVED -> R.string.flash_arrived
        FlashKind.CALL_YOU_BACK -> R.string.flash_call_you_back
    }

/** What to tell the user about [error]. */
@StringRes
internal fun AppError.message(): Int =
    when (this) {
        AppError.NotFound -> R.string.reach_not_reachable
        AppError.Network, AppError.ServerNotConfigured -> R.string.error_network
        is AppError.RateLimited -> R.string.error_rate_limited
        else -> R.string.error_generic
    }
