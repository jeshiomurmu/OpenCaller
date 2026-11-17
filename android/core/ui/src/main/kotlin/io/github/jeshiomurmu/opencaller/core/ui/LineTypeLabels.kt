package io.github.jeshiomurmu.opencaller.core.ui

import androidx.annotation.StringRes
import io.github.jeshiomurmu.opencaller.core.model.LineType

/** Short label of a line type, or `null` when unknown. */
@StringRes
internal fun LineType.labelRes(): Int? =
    when (this) {
        LineType.FIXED_LINE -> R.string.line_fixed
        LineType.MOBILE -> R.string.line_mobile
        LineType.FIXED_LINE_OR_MOBILE -> R.string.line_fixed_or_mobile
        LineType.TOLL_FREE -> R.string.line_toll_free
        LineType.PREMIUM_RATE -> R.string.line_premium_rate
        LineType.SHARED_COST -> R.string.line_shared_cost
        LineType.VOIP -> R.string.line_voip
        LineType.PERSONAL_NUMBER -> R.string.line_personal
        LineType.PAGER -> R.string.line_pager
        LineType.UAN -> R.string.line_uan
        LineType.VOICEMAIL -> R.string.line_voicemail
        LineType.UNKNOWN -> null
    }
