package io.github.jeshiomurmu.opencaller.feature.incall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallController
import javax.inject.Inject

/**
 * Decline and Hang up on the call notifications. Not exported: only OpenCaller's own PendingIntents reach it.
 * (Answer opens the in-call screen instead, so the user sees the call they picked up.)
 */
@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var calls: CallController

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val callId = intent.getStringExtra(InCallIntents.EXTRA_CALL_ID) ?: return
        when (intent.action) {
            ACTION_DECLINE -> calls.decline(callId)
            ACTION_HANG_UP -> calls.hangUp(callId)
        }
    }

    /** Actions. */
    companion object {
        /** Decline the ringing call. */
        const val ACTION_DECLINE = "io.github.jeshiomurmu.opencaller.action.DECLINE_CALL"

        /** Hang up the call. */
        const val ACTION_HANG_UP = "io.github.jeshiomurmu.opencaller.action.HANG_UP_CALL"
    }
}
