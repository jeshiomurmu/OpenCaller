package io.github.jeshiomurmu.opencaller.feature.incall

import android.content.Context
import android.content.Intent
import javax.inject.Inject
import javax.inject.Singleton

/** Intents that open the in-call screen. */
object InCallIntents {
    /** Answer the call [EXTRA_CALL_ID], then show the in-call screen. */
    const val ACTION_ANSWER = "io.github.jeshiomurmu.opencaller.action.ANSWER_CALL"

    /** The call an action is for ([io.github.jeshiomurmu.opencaller.core.telephony.incall.TrackedCall.id]). */
    const val EXTRA_CALL_ID = "io.github.jeshiomurmu.opencaller.extra.CALL_ID"

    /** Open the keypad as well. */
    const val EXTRA_SHOW_DIALPAD = "io.github.jeshiomurmu.opencaller.extra.SHOW_DIALPAD"

    /** Shows the in-call screen (a new task: it is started from a service or a notification). */
    fun show(
        context: Context,
        showDialpad: Boolean = false,
    ): Intent =
        Intent(context, InCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            .putExtra(EXTRA_SHOW_DIALPAD, showDialpad)

    /** Answers [callId] and shows the in-call screen. */
    fun answer(
        context: Context,
        callId: String,
    ): Intent = show(context).setAction(ACTION_ANSWER).putExtra(EXTRA_CALL_ID, callId)
}

/** Whether the in-call screen is in front, so the after-call screen may follow it. Main thread only. */
@Singleton
class InCallVisibility
    @Inject
    constructor() {
        /** True between the in-call screen's onResume and onPause. */
        var isVisible: Boolean = false
            internal set
    }
