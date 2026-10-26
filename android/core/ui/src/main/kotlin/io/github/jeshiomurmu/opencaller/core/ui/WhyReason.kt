package io.github.jeshiomurmu.opencaller.core.ui

import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.CallerVerification
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.model.VerdictSource

/** One line of "Why am I seeing this?": where a name, a warning or a block came from. */
sealed interface WhyReason {
    /** A blocking rule the user made matched. */
    data object YourRule : WhyReason

    /** "Block hidden numbers" is on. */
    data object HiddenSetting : WhyReason

    /** "Block calls from other countries" is on. */
    data object ForeignSetting : WhyReason

    /** "Block numbers not in contacts" is on. */
    data object NonContactSetting : WhyReason

    /** The user's own contact. */
    data object Contact : WhyReason

    /** The number's owner registered the name (confirmed by code). */
    data object SelfRegistered : WhyReason

    /** A business OpenCaller verified. */
    data object VerifiedBusiness : WhyReason

    /** The spam list saved on the phone. */
    data object SpamList : WhyReason

    /** An earlier private lookup, kept for 24 hours. */
    data object EarlierLookup : WhyReason

    /** A private lookup during the call (only a short code of the scrambled number was sent). */
    data object LiveLookup : WhyReason

    /** The user searched for the number (sent in full, by their choice). */
    data object Searched : WhyReason

    /** [count] community reports. */
    data class Reports(
        val count: Int,
    ) : WhyReason

    /** The carrier verified the number (STIR/SHAKEN). */
    data object CarrierVerified : WhyReason

    /** The carrier's verification failed: possibly spoofed. */
    data object CarrierFailed : WhyReason

    /** Nothing is known about the number. */
    data object NoData : WhyReason
}

/**
 * The reasons behind what OpenCaller shows for [caller] and, for a screened call, the engine's [reason]: the
 * decision first (a rule or setting), then where the name came from, then where the spam verdict came from.
 */
fun whyReasons(
    caller: CallerInfo?,
    reason: ScreeningReason? = null,
): List<WhyReason> =
    buildList {
        decisionReason(reason)?.let(::add)
        caller
            ?.nameSource
            ?.takeIf { caller.displayName != null }
            ?.let(::nameReason)
            ?.let(::add)
        caller?.spamVerdict?.let { verdict ->
            add(
                when (verdict.source ?: reason.verdictSource()) {
                    VerdictSource.SPAM_LIST -> WhyReason.SpamList
                    VerdictSource.REPUTATION_CACHE -> WhyReason.EarlierLookup
                    VerdictSource.LIVE_LOOKUP -> WhyReason.LiveLookup
                    VerdictSource.SEARCH -> WhyReason.Searched
                },
            )
            if (verdict.reportCount > 0) add(WhyReason.Reports(verdict.reportCount))
        }
        when (caller?.verification) {
            CallerVerification.PASSED -> add(WhyReason.CarrierVerified)
            CallerVerification.FAILED -> add(WhyReason.CarrierFailed)
            else -> Unit
        }
        if (isEmpty()) add(WhyReason.NoData)
    }

private fun decisionReason(reason: ScreeningReason?): WhyReason? =
    when (reason) {
        ScreeningReason.RULE -> WhyReason.YourRule
        ScreeningReason.HIDDEN -> WhyReason.HiddenSetting
        ScreeningReason.FOREIGN -> WhyReason.ForeignSetting
        ScreeningReason.NON_CONTACT -> WhyReason.NonContactSetting
        else -> null
    }

private fun nameReason(source: NameSource): WhyReason? =
    when (source) {
        NameSource.CONTACT -> WhyReason.Contact
        NameSource.SELF_REGISTERED -> WhyReason.SelfRegistered
        NameSource.VERIFIED_BUSINESS -> WhyReason.VerifiedBusiness
        else -> null
    }

// A verdict without a recorded source: a block by the live lookup, otherwise the saved spam list.
private fun ScreeningReason?.verdictSource(): VerdictSource =
    if (this == ScreeningReason.NETWORK) VerdictSource.LIVE_LOOKUP else VerdictSource.SPAM_LIST
