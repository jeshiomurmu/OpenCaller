package io.github.jeshiomurmu.opencaller.core.telephony.screening

import android.telecom.Call
import android.telecom.CallScreeningService
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.telephony.screening.device.callResponseFor
import io.github.jeshiomurmu.opencaller.core.telephony.screening.device.toScreeningCall
import timber.log.Timber
import javax.inject.Inject

/**
 * Android's entry point for call screening (docs/architecture.md §4.1): reads the call, lets [ScreeningCoordinator]
 * decide within the 1,500 ms budget and answers with the matching [CallResponse]. Outgoing calls and emergency
 * numbers are always allowed. If the call can't even be read, it is allowed at once (fail open).
 */
@AndroidEntryPoint
class OpenCallerScreeningService : CallScreeningService() {
    @Inject
    lateinit var coordinator: ScreeningCoordinator

    // Call.Details comes from the platform; anything unexpected in it must still end in "allow".
    @Suppress("TooGenericExceptionCaught")
    override fun onScreenCall(callDetails: Call.Details) {
        val call =
            try {
                callDetails.toScreeningCall()
            } catch (e: RuntimeException) {
                Timber.w(e, "Unreadable call details; allowing the call")
                respond(callDetails, Decision.ALLOW)
                return
            }
        coordinator.screen(call) { decision -> respond(callDetails, decision) }
    }

    private fun respond(
        details: Call.Details,
        decision: Decision,
    ) {
        respondToCall(details, callResponseFor(decision))
    }
}
