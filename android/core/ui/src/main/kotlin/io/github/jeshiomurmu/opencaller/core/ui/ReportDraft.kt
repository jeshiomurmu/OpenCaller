package io.github.jeshiomurmu.opencaller.core.ui

import io.github.jeshiomurmu.opencaller.core.model.ReportCategory
import io.github.jeshiomurmu.opencaller.core.model.ReportLabel

/**
 * What the user reports about a number.
 *
 * @property category spam reports only.
 * @property alsoBlock add a block rule for the number too (spam reports only).
 */
data class ReportDraft(
    val label: ReportLabel,
    val category: ReportCategory?,
    val comment: String,
    val alsoBlock: Boolean,
)

/** Longest comment the server accepts (core:data `MAX_REPORT_COMMENT`). */
const val REPORT_COMMENT_MAX_LENGTH = 280
