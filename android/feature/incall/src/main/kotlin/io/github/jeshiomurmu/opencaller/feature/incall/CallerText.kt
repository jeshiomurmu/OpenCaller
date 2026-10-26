package io.github.jeshiomurmu.opencaller.feature.incall

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.incall.TrackedCall
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import io.github.jeshiomurmu.opencaller.core.ui.CallerCardState
import javax.inject.Inject
import io.github.jeshiomurmu.opencaller.core.ui.R as UiR

/** Numbers for display (docs/design.md §7): national format in the user's country, international otherwise. */
class CallNumbers
    @Inject
    constructor(
        private val normalizer: PhoneNumberNormalizer,
        private val homeRegion: HomeRegionProvider,
    ) {
        /** [call]'s number for display, or the raw handle when it isn't a phone number (e.g. 911); `null` when hidden. */
        fun number(
            call: TrackedCall,
            caller: CallerInfo?,
        ): String? = caller?.number?.let { normalizer.format(it, homeRegion.homeRegion()) } ?: call.handle.takeUnless { call.hidden }
    }

/** Plain-text lines about a caller, for notifications. */
internal class CallerText
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val numbers: CallNumbers,
    ) {
        /** See [CallNumbers.number]. */
        fun number(
            call: TrackedCall,
            caller: CallerInfo?,
        ): String? = numbers.number(call, caller)

        /** Who is on the call: a name with a source, else the number, else "Hidden number". */
        fun title(
            call: TrackedCall,
            caller: CallerInfo?,
        ): String =
            caller?.displayName?.takeIf { caller.nameSource != null }
                ?: number(call, caller)
                ?: context.getString(UiR.string.caller_card_hidden)

        /** "Likely scam" or "Suspicious" for a flagged caller (risk is never colour alone), else `null`. */
        fun risk(caller: CallerInfo?): String? =
            when (caller?.let { CallerCardState.of(it) }) {
                CallerCardState.SPAM -> context.getString(UiR.string.risk_likely_scam)
                CallerCardState.SUSPICIOUS -> context.getString(UiR.string.risk_suspicious)
                else -> null
            }
    }
