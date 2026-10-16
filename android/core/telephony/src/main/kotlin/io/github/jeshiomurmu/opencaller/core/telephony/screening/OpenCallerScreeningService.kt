package io.github.jeshiomurmu.opencaller.core.telephony.screening

import android.telecom.Call
import android.telecom.CallScreeningService

/**
 * Lets every call through. It exists so Android can give OpenCaller the call screening role (the role
 * requires a [CallScreeningService]); the screening engine replaces this behaviour in Phase 3.
 */
class OpenCallerScreeningService : CallScreeningService() {
    override fun onScreenCall(callDetails: Call.Details) {
        // An empty response allows the call: no block, no reject, no silence, no skipped call log entry.
        respondToCall(callDetails, CallResponse.Builder().build())
    }
}
