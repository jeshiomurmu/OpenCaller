package io.github.jeshiomurmu.opencaller.core.telephony.screening.device

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService.CallResponse
import android.telecom.Connection
import android.telecom.PhoneAccount
import io.github.jeshiomurmu.opencaller.core.model.CallerVerification
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningCall

/**
 * Reads what Android gives a call screening service. Only the direction, the handle and (Android 11+) the STIR/SHAKEN
 * status are set for us; the handle's presentation is not, so a missing handle is what means "hidden". Android only
 * screens `tel:` calls; any other handle is kept as it is and reads as an unknown format (the call rings).
 */
fun Call.Details.toScreeningCall(): ScreeningCall =
    screeningCall(
        number = handle?.let { if (it.scheme == PhoneAccount.SCHEME_TEL) it.schemeSpecificPart else it.toString() },
        callDirection = callDirection,
        verificationStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) callerNumberVerificationStatus else null,
    )

/** [toScreeningCall] on plain values, so the mapping is testable without a `Call.Details`. */
internal fun screeningCall(
    number: String?,
    callDirection: Int,
    verificationStatus: Int?,
): ScreeningCall =
    ScreeningCall(
        handle = number,
        direction = if (callDirection == Call.Details.DIRECTION_OUTGOING) Direction.OUTGOING else Direction.INCOMING,
        verification = verificationStatus.toVerification(),
    )

private fun Int?.toVerification(): CallerVerification =
    when (this) {
        Connection.VERIFICATION_STATUS_PASSED -> CallerVerification.PASSED
        Connection.VERIFICATION_STATUS_FAILED -> CallerVerification.FAILED
        Connection.VERIFICATION_STATUS_NOT_VERIFIED -> CallerVerification.NOT_VERIFIED
        else -> CallerVerification.UNKNOWN
    }

/**
 * The response for [decision] (docs/architecture.md §4.1). BLOCK rejects the call without a notification; SILENCE
 * lets it through without ringing; ALLOW changes nothing. `setSkipCallLog` is left off: Android ignores it for apps
 * that aren't the system dialer, and blocked calls should stay visible in the call log anyway.
 */
fun callResponseFor(decision: Decision): CallResponse =
    when (decision) {
        Decision.ALLOW -> {
            CallResponse.Builder().build()
        }

        Decision.SILENCE -> {
            CallResponse.Builder().setSilenceCall(true).build()
        }

        Decision.BLOCK -> {
            CallResponse
                .Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipNotification(true)
                .build()
        }
    }
