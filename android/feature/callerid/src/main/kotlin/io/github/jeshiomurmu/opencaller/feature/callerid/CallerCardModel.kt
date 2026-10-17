package io.github.jeshiomurmu.opencaller.feature.callerid

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.CallerVerification
import io.github.jeshiomurmu.opencaller.core.model.ReportCategory
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import io.github.jeshiomurmu.opencaller.core.ui.CallerCardState
import io.github.jeshiomurmu.opencaller.core.ui.labelRes
import javax.inject.Inject
import io.github.jeshiomurmu.opencaller.core.ui.R as UiR

/**
 * What caller ID shows for one call.
 *
 * @property formattedNumber the number in national format when it is from the user's country (docs/design.md §7).
 * @property maskedNumber the number with its middle hidden, for confirmations.
 */
data class CallerCardModel(
    val caller: CallerInfo,
    val formattedNumber: String?,
    val maskedNumber: String?,
) {
    /** The card state (docs/design.md §5). */
    val state: CallerCardState get() = CallerCardState.of(caller)
}

/** Builds [CallerCardModel]s and the plain-text lines notifications use. */
class CallerCardFormatter
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val normalizer: PhoneNumberNormalizer,
        private val homeRegion: HomeRegionProvider,
    ) {
        /** The card for [caller]. */
        fun card(caller: CallerInfo): CallerCardModel =
            CallerCardModel(
                caller = caller,
                formattedNumber = caller.number?.let { normalizer.format(it, homeRegion.homeRegion()) },
                maskedNumber = caller.number?.let(normalizer::masked),
            )

        /** Who is calling: the name with a source, else the number, else "Hidden number" / "Unknown caller". */
        fun title(card: CallerCardModel): String =
            card.caller.displayName?.takeIf { card.caller.nameSource != null }
                ?: card.formattedNumber
                ?: context.getString(
                    if (card.state ==
                        CallerCardState.HIDDEN
                    ) {
                        UiR.string.caller_card_hidden
                    } else {
                        UiR.string.caller_card_unknown
                    },
                )

        /** One line under the title, in words (risk is never colour alone), e.g. "Likely scam · Loan · 214 reports". */
        fun summary(card: CallerCardModel): String {
            val caller = card.caller
            val parts = mutableListOf(stateLine(card))
            caller.spamVerdict?.takeIf { it.level != RiskLevel.NONE }?.let { verdict ->
                verdict.category
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::categoryText)
                    ?.let(parts::add)
                if (verdict.reportCount > 0) {
                    parts += context.resources.getQuantityString(UiR.plurals.risk_report_count, verdict.reportCount, verdict.reportCount)
                }
            }
            when (caller.verification) {
                CallerVerification.PASSED -> {
                    parts += context.getString(UiR.string.caller_card_stir_passed)
                }

                CallerVerification.FAILED -> {
                    parts += context.getString(UiR.string.caller_card_stir_failed)
                }

                CallerVerification.NOT_VERIFIED, CallerVerification.UNKNOWN -> {}
            }
            return parts.joinToString(context.getString(UiR.string.risk_banner_separator))
        }

        // Known categories in the user's language; anything else as the server sent it.
        private fun categoryText(code: String): String = ReportCategory.fromCode(code)?.let { context.getString(it.labelRes()) } ?: code

        private fun stateLine(card: CallerCardModel): String =
            context.getString(
                when (card.state) {
                    CallerCardState.CONTACT -> UiR.string.source_contact
                    CallerCardState.SELF_REGISTERED -> UiR.string.source_self_registered
                    CallerCardState.VERIFIED_BUSINESS -> UiR.string.source_verified_business
                    CallerCardState.SPAM -> UiR.string.risk_likely_scam
                    CallerCardState.SUSPICIOUS -> UiR.string.risk_suspicious
                    CallerCardState.UNKNOWN -> UiR.string.caller_card_no_data
                    CallerCardState.HIDDEN -> UiR.string.caller_card_hidden_note
                },
            )
    }
