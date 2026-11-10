package io.github.jeshiomurmu.opencaller.feature.recording

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RecordingLine
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.ConferenceAction
import io.github.jeshiomurmu.opencaller.core.telephony.incall.InCallState
import io.github.jeshiomurmu.opencaller.core.telephony.incall.RecordingFailure
import io.github.jeshiomurmu.opencaller.core.telephony.incall.mergeAction

/** How the phone talks to the recording line: pure functions, tested on the JVM. */
internal object LinePlan {
    /**
     * The number to dial: the line, two pauses (`,`, two seconds each, so the line has answered and played its ready
     * signal), then the code and `#`. Telecom sends the digits after the pauses as tones.
     */
    fun dialString(line: RecordingLine): String = "${line.number.e164},,${line.code}#"

    /** True when a call's [handle] is the line [number] (Telecom may show it without `+`, in national form, or with the tones). */
    fun isLine(
        handle: String?,
        number: PhoneNumber,
    ): Boolean {
        val digits = handle?.substringBefore(',')?.substringBefore(';')?.filter(Char::isDigit) ?: return false
        val line = number.e164.filter(Char::isDigit)
        return digits.length >= MIN_DIGITS && (line.endsWith(digits) || digits.endsWith(line))
    }

    /** The reason to show for a server [error] when asking for the line. */
    fun failureOf(error: AppError): RecordingFailure =
        when (error) {
            is AppError.Validation -> {
                if (error.message == SUPPORTER_REQUIRED) RecordingFailure.SUPPORTER_REQUIRED else RecordingFailure.UNAVAILABLE
            }

            AppError.Auth, AppError.ServerNotConfigured -> {
                RecordingFailure.NOT_SIGNED_IN
            }

            AppError.NotFound -> {
                RecordingFailure.UNAVAILABLE
            }

            else -> {
                RecordingFailure.NETWORK
            }
        }

    /**
     * How to merge the answered line [lineCall] with the user's call [callId]: directly when Telecom says they are
     * conferenceable, else whatever merge the calls offer (an IMS conference), or `null` while neither is possible.
     */
    fun mergeOf(
        state: InCallState,
        lineCall: String,
        callId: String,
    ): ConferenceAction? {
        val line = state.call(lineCall)?.takeIf { it.status == CallStatus.ACTIVE } ?: return null
        return if (callId in line.conferenceableIds) ConferenceAction.Merge(lineCall, callId) else state.mergeAction()
    }

    private const val MIN_DIGITS = 7
    private const val SUPPORTER_REQUIRED = "supporter_required"
}
