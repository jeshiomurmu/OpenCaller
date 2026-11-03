package io.github.jeshiomurmu.opencaller.feature.callerid.announce

import androidx.annotation.StringRes
import io.github.jeshiomurmu.opencaller.core.model.AnnounceCallerMode
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.feature.callerid.R

/**
 * The phone's audio state when a call rings.
 *
 * @property headphones wired, USB or Bluetooth headphones are connected.
 * @property ringerAudible the ringer is on (not silent or vibrate).
 * @property interruptionsAllowed Do Not Disturb isn't filtering (the interruption filter lets everything through).
 */
data class AudioConditions(
    val headphones: Boolean,
    val ringerAudible: Boolean,
    val interruptionsAllowed: Boolean,
)

/** What to say, as a string resource and its one argument (or none). */
data class Announcement(
    @param:StringRes val text: Int,
    val name: String? = null,
)

/** When and what Announce Caller speaks (docs/prd.md F-UT-02). */
object AnnouncePolicy {
    /**
     * True when the caller should be announced: a supporter chose [mode] ALWAYS, or HEADSET_ONLY with headphones on,
     * and the phone would ring audibly (ringer on, Do Not Disturb not filtering). A silent phone stays silent.
     */
    fun shouldAnnounce(
        mode: AnnounceCallerMode,
        entitled: Boolean,
        audio: AudioConditions,
    ): Boolean {
        val wanted =
            when (mode) {
                AnnounceCallerMode.OFF -> false
                AnnounceCallerMode.ALWAYS -> true
                AnnounceCallerMode.HEADSET_ONLY -> audio.headphones
            }
        return wanted && entitled && audio.ringerAudible && audio.interruptionsAllowed
    }

    /** The name if one is known (contact, verified business, the number's owner), else the spam warning or "unknown". */
    fun announcementFor(caller: CallerInfo): Announcement {
        val name = caller.displayName?.takeIf { it.isNotBlank() }
        return when {
            caller.spamVerdict?.level == RiskLevel.LIKELY_SPAM -> Announcement(R.string.announce_spam)
            name != null -> Announcement(R.string.announce_named, name)
            caller.isHidden -> Announcement(R.string.announce_hidden)
            else -> Announcement(R.string.announce_unknown)
        }
    }
}
