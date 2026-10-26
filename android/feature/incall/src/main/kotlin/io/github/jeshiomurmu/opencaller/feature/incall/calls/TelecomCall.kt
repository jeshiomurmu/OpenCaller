package io.github.jeshiomurmu.opencaller.feature.incall.calls

import io.github.jeshiomurmu.opencaller.core.telephony.incall.TrackedCall

/**
 * The parts of one `android.telecom.Call` that [CallRegistry] uses. [AndroidTelecomCall] wraps the real call; tests
 * use a fake.
 */
@Suppress("TooManyFunctions") // One method per operation of android.telecom.Call the in-call screen uses.
internal interface TelecomCall {
    /** The underlying call object; other calls refer to this one by it (conference, merge partners). */
    val key: Any

    /** The call as the in-call screen sees it. [idOf] names the calls this one refers to, by their [key]. */
    fun snapshot(
        id: String,
        idOf: (Any) -> String?,
    ): TrackedCall

    /** Starts telling [onChange] about every change of the call (state, details, conference, quick replies). */
    fun observe(onChange: () -> Unit)

    /** Stops [observe]. */
    fun stopObserving()

    /** Answers (audio only). */
    fun answer()

    /** Declines, with an SMS to the caller when [message] is not `null`. */
    fun reject(message: String?)

    /** Hangs up. */
    fun disconnect()

    /** Puts the call on hold. */
    fun hold()

    /** Takes the call off hold. */
    fun unhold()

    /** Starts a keypad tone. */
    fun playTone(digit: Char)

    /** Ends the keypad tone. */
    fun stopTone()

    /** Joins this call and [other] into a conference. */
    fun conference(other: TelecomCall)

    /** Merges the calls of this conference. */
    fun mergeConference()

    /** Swaps the calls of this conference. */
    fun swapConference()

    /** Places this call (waiting for a SIM) with the SIM [simId] from its [TrackedCall.simChoices]. */
    fun selectSim(simId: String)
}

/** Audio controls of the in-call service (they act on every call). */
internal interface AudioControl {
    /** Mutes or unmutes the microphone. */
    fun setMuted(muted: Boolean)

    /** Sends call audio to the route [routeId]. */
    fun route(routeId: String)
}
