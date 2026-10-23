package io.github.jeshiomurmu.opencaller.core.telephony.incall

import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.NameSource

/**
 * A call that just ended while OpenCaller was the default phone app.
 *
 * @property answered true when the call connected (false for a missed or declined call).
 */
data class EndedCall(
    val caller: CallerInfo,
    val direction: Direction?,
    val answered: Boolean,
) {
    /**
     * True when the after-call card follows (docs/architecture.md §4.3): an incoming call from a number that isn't a
     * contact, as in screening-only mode. Hidden and emergency callers have no number and get none.
     */
    val wantsAfterCall: Boolean
        get() = direction == Direction.INCOMING && caller.number != null && caller.nameSource != NameSource.CONTACT
}

/**
 * Shows the after-call card (`feature:callerid`): as a screen when the in-call screen was in front and the phone is
 * unlocked (Android only lets an app start a screen while it is visible), otherwise as a notification.
 */
fun interface AfterCallPresenter {
    /** Called on the main thread once [call] has ended. */
    fun present(
        call: EndedCall,
        inCallScreenVisible: Boolean,
    )
}
