package io.github.jeshiomurmu.opencaller.core.ui

import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel

/** The seven caller card states of docs/design.md §5. */
enum class CallerCardState {
    /** Someone in the user's contacts. */
    CONTACT,

    /** The number's owner registered their name. */
    SELF_REGISTERED,

    /** A verified business. */
    VERIFIED_BUSINESS,

    /** Likely spam or scam. */
    SPAM,

    /** Some spam signals. */
    SUSPICIOUS,

    /** Nothing known about the number. */
    UNKNOWN,

    /** The caller hid their number. */
    HIDDEN,
    ;

    /** How the card looks for a caller. */
    companion object {
        /** The state for [caller]. A contact never shows as spam; a spam verdict beats a business name. */
        fun of(caller: CallerInfo): CallerCardState {
            val level = caller.spamVerdict?.level ?: RiskLevel.NONE
            return when {
                caller.isHidden -> HIDDEN
                caller.nameSource == NameSource.CONTACT -> CONTACT
                level == RiskLevel.LIKELY_SPAM -> SPAM
                caller.isVerifiedBusiness || caller.nameSource == NameSource.VERIFIED_BUSINESS -> VERIFIED_BUSINESS
                level == RiskLevel.SUSPICIOUS -> SUSPICIOUS
                caller.nameSource == NameSource.SELF_REGISTERED -> SELF_REGISTERED
                else -> UNKNOWN
            }
        }
    }
}
