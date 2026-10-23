package io.github.jeshiomurmu.opencaller.core.telephony.incall

import kotlinx.coroutines.flow.StateFlow

/**
 * The calls OpenCaller's in-call service is showing (it only gets them as the default phone app). Updated on the main
 * thread from Telecom's callbacks.
 */
interface CallStateRepository {
    /** Every call and the audio state; empty while there is no call. */
    val state: StateFlow<InCallState>
}

/**
 * What the in-call screen and the call notifications can ask Telecom to do. Calls are named by [TrackedCall.id]; an id
 * that no longer exists is ignored. Call on the main thread.
 */
@Suppress("TooManyFunctions") // One command per operation Telecom offers on a call.
interface CallController {
    /** Answers a ringing call (audio only). */
    fun answer(callId: String)

    /** Declines a ringing call. */
    fun decline(callId: String)

    /** Declines a ringing call and sends [message] to the caller by SMS. */
    fun declineWithMessage(
        callId: String,
        message: String,
    )

    /** Hangs up. */
    fun hangUp(callId: String)

    /** Puts a call on hold. */
    fun hold(callId: String)

    /** Takes a call off hold (Telecom holds the other call first when it can). */
    fun resume(callId: String)

    /** Starts the keypad tone for [digit] (0–9, *, #); [stopTone] ends it. */
    fun playTone(
        callId: String,
        digit: Char,
    )

    /** Ends the keypad tone. */
    fun stopTone(callId: String)

    /** Runs a merge or swap worked out by [mergeAction] or [swapAction]. */
    fun perform(action: ConferenceAction)

    /** Mutes or unmutes the microphone. */
    fun setMuted(muted: Boolean)

    /** Sends call audio to [routeId] (one of [CallAudio.available]). */
    fun route(routeId: String)

    /** Places a call that is waiting for a SIM with [simId] (one of [TrackedCall.simChoices]). */
    fun selectSim(
        callId: String,
        simId: String,
    )

    /** Reads every call again (for example after the user allowed reading SIM names). */
    fun refresh()
}
