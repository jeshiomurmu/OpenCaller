package io.github.jeshiomurmu.opencaller.core.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.jeshiomurmu.opencaller.core.model.ReportCategory

/** Translated name of a report category. */
@StringRes
fun ReportCategory.labelRes(): Int =
    when (this) {
        ReportCategory.SCAM -> R.string.category_scam
        ReportCategory.FRAUD -> R.string.category_fraud
        ReportCategory.TELEMARKETING -> R.string.category_telemarketing
        ReportCategory.LOAN -> R.string.category_loan
        ReportCategory.DEBT_COLLECTOR -> R.string.category_debt_collector
        ReportCategory.ROBOCALL -> R.string.category_robocall
        ReportCategory.SURVEY -> R.string.category_survey
        ReportCategory.POLITICAL -> R.string.category_political
        ReportCategory.CHARITY -> R.string.category_charity
        ReportCategory.NUISANCE -> R.string.category_nuisance
        ReportCategory.OTHER -> R.string.category_other
    }

/** A category as stored or sent by the server: translated when known, else as given; `null` for none. */
@Composable
fun categoryLabel(code: String?): String? {
    val known = ReportCategory.fromCode(code)
    return when {
        known != null -> stringResource(known.labelRes())
        code.isNullOrBlank() -> null
        else -> code
    }
}
