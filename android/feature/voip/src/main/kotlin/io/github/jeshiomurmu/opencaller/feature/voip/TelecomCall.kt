package io.github.jeshiomurmu.opencaller.feature.voip

import android.content.Context
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** What Telecom asks of an app call. */
internal interface TelecomEvents {
    /** Telecom answered the call (a headset button, a car, the system UI). */
    suspend fun onTelecomAnswer()

    /** Telecom ended the call (the system or another app). */
    suspend fun onTelecomDisconnect(cause: DisconnectCause)

    /** The call's audio moved to the speaker ([on]) or away from it. */
    fun onSpeakerChanged(on: Boolean)
}

/**
 * One app call registered with Jetpack Telecom as a self-managed call, so Android treats it like a phone call:
 * other calls hold or end it, Bluetooth and cars can answer it, and Telecom routes its audio.
 */
internal class TelecomCall(
    context: Context,
) {
    private val manager = CallsManager(context)
    private var control: CallControlScope? = null

    /** Adds the call and suspends until it ends. Throws `CallException` when Telecom refuses it. */
    suspend fun run(
        displayName: String,
        number: String,
        incoming: Boolean,
        events: TelecomEvents,
    ) {
        manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
        val attributes =
            CallAttributesCompat(
                displayName = displayName,
                address = Uri.fromParts(SCHEME, number, null),
                direction = if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
                callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
                callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
            )
        manager.addCall(
            attributes,
            onAnswer = { events.onTelecomAnswer() },
            onDisconnect = { events.onTelecomDisconnect(it) },
            onSetActive = {},
            onSetInactive = {},
        ) {
            control = this
            launch { currentCallEndpoint.collect { events.onSpeakerChanged(it.type == CallEndpointCompat.TYPE_SPEAKER) } }
        }
    }

    /** Answers the call here. */
    suspend fun answer() {
        control?.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)
    }

    /** Marks the call active (both parties connected). */
    suspend fun setActive() {
        control?.setActive()
    }

    /** Ends the call with [code] (a `DisconnectCause` code). */
    suspend fun disconnect(code: Int) {
        control?.disconnect(DisconnectCause(code))
        control = null
    }

    /** Moves the audio to the speaker, or back to the earpiece (or a headset when one is there). */
    suspend fun setSpeaker(on: Boolean) {
        val scope = control ?: return
        val endpoints = scope.availableEndpoints.first()
        val target =
            if (on) {
                endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
            } else {
                endpoints.firstOrNull { it.type != CallEndpointCompat.TYPE_SPEAKER && it.type != CallEndpointCompat.TYPE_STREAMING }
            }
        target?.let { scope.requestEndpointChange(it) }
    }

    private companion object {
        // The address labels the call in the system's call surfaces (cars, watches).
        const val SCHEME = "tel"
    }
}
