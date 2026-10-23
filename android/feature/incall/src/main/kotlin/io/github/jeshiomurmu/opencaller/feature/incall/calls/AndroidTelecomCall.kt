package io.github.jeshiomurmu.opencaller.feature.incall.calls

import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.PhoneAccountHandle
import android.telecom.PhoneAccountSuggestion
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallCapability
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.SimChoice
import io.github.jeshiomurmu.opencaller.core.telephony.incall.TrackedCall

/**
 * [TelecomCall] over a real [Call] (developer.android.com, `android.telecom.Call`). [simLabel] reads a SIM's name,
 * or returns `null` when it can't (Android 12+ needs `READ_PHONE_NUMBERS` for that).
 */
@Suppress("TooManyFunctions") // TelecomCall's methods, one per android.telecom.Call operation.
internal class AndroidTelecomCall(
    val call: Call,
    private val simLabel: (PhoneAccountHandle) -> String?,
) : TelecomCall {
    private var callback: Call.Callback? = null

    override val key: Any get() = call

    override fun snapshot(
        id: String,
        idOf: (Any) -> String?,
    ): TrackedCall {
        val details = call.details
        val handle = details.handle?.schemeSpecificPart?.takeIf(String::isNotBlank)
        val status = statusOf(stateOf(call))
        return TrackedCall(
            id = id,
            handle = handle,
            hidden = handle == null || details.handlePresentation != TelecomManager.PRESENTATION_ALLOWED,
            status = status,
            direction = directionOf(details.callDirection),
            capabilities = capabilitiesOf(details),
            isConference = details.hasProperty(Call.Details.PROPERTY_CONFERENCE),
            parentId = call.parent?.let(idOf),
            childIds = call.children.mapNotNull(idOf),
            conferenceableIds = call.conferenceableCalls.mapNotNull(idOf),
            connectedAtMillis = details.connectTimeMillis.coerceAtLeast(0),
            cannedReplies = call.cannedTextResponses.orEmpty(),
            simChoices = if (status == CallStatus.SELECT_SIM) simChoices(details.extras) else emptyList(),
        )
    }

    override fun observe(onChange: () -> Unit) {
        stopObserving()
        val listener =
            object : Call.Callback() {
                override fun onStateChanged(
                    call: Call,
                    state: Int,
                ) = onChange()

                override fun onDetailsChanged(
                    call: Call,
                    details: Call.Details,
                ) = onChange()

                override fun onParentChanged(
                    call: Call,
                    parent: Call?,
                ) = onChange()

                override fun onChildrenChanged(
                    call: Call,
                    children: List<Call>,
                ) = onChange()

                override fun onConferenceableCallsChanged(
                    call: Call,
                    conferenceableCalls: List<Call>,
                ) = onChange()

                override fun onCannedTextResponsesLoaded(
                    call: Call,
                    cannedTextResponses: List<String>,
                ) = onChange()
            }
        callback = listener
        call.registerCallback(listener)
    }

    override fun stopObserving() {
        callback?.let(call::unregisterCallback)
        callback = null
    }

    override fun answer() = call.answer(VideoProfile.STATE_AUDIO_ONLY)

    override fun reject(message: String?) = call.reject(message != null, message)

    override fun disconnect() = call.disconnect()

    override fun hold() = call.hold()

    override fun unhold() = call.unhold()

    override fun playTone(digit: Char) = call.playDtmfTone(digit)

    override fun stopTone() = call.stopDtmfTone()

    override fun conference(other: TelecomCall) {
        (other.key as? Call)?.let(call::conference)
    }

    override fun mergeConference() = call.mergeConference()

    override fun swapConference() = call.swapConference()

    override fun selectSim(simId: String) {
        suggestedAccounts(call.details.extras)
            .getOrNull(simId.toIntOrNull() ?: -1)
            ?.let { call.phoneAccountSelected(it, false) }
    }

    private fun simChoices(extras: Bundle?): List<SimChoice> =
        suggestedAccounts(extras).mapIndexed { index, handle -> SimChoice(index.toString(), simLabel(handle)) }
}

/** The suggested SIMs Telecom attaches to a call waiting in [Call.STATE_SELECT_PHONE_ACCOUNT], in its order. */
internal fun suggestedAccounts(extras: Bundle?): List<PhoneAccountHandle> {
    val suggested = extras?.let(::suggestions).orEmpty().map(PhoneAccountSuggestion::getPhoneAccountHandle)
    // Older Telecom builds only fill the deprecated list (same order and length, per Call.EXTRA_SUGGESTED_PHONE_ACCOUNTS).
    return suggested.ifEmpty { extras?.let(::availableAccounts).orEmpty() }
}

private fun suggestions(extras: Bundle): List<PhoneAccountSuggestion>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        extras.getParcelableArrayList(Call.EXTRA_SUGGESTED_PHONE_ACCOUNTS, PhoneAccountSuggestion::class.java)
    } else {
        @Suppress("DEPRECATION")
        extras.getParcelableArrayList<PhoneAccountSuggestion>(Call.EXTRA_SUGGESTED_PHONE_ACCOUNTS)
    }

@Suppress("DEPRECATION")
private fun availableAccounts(extras: Bundle): List<PhoneAccountHandle>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        extras.getParcelableArrayList(Call.AVAILABLE_PHONE_ACCOUNTS, PhoneAccountHandle::class.java)
    } else {
        extras.getParcelableArrayList<PhoneAccountHandle>(Call.AVAILABLE_PHONE_ACCOUNTS)
    }

// Call.getState is deprecated from API 31 in favour of Call.Details.getState.
private fun stateOf(call: Call): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        call.details.state
    } else {
        @Suppress("DEPRECATION")
        call.state
    }

/** [Call] state constants to [CallStatus]. */
internal fun statusOf(state: Int): CallStatus =
    when (state) {
        Call.STATE_NEW, Call.STATE_CONNECTING, Call.STATE_PULLING_CALL -> CallStatus.CONNECTING
        Call.STATE_DIALING -> CallStatus.DIALING
        Call.STATE_RINGING, Call.STATE_SIMULATED_RINGING -> CallStatus.RINGING
        Call.STATE_ACTIVE -> CallStatus.ACTIVE
        Call.STATE_HOLDING -> CallStatus.HOLDING
        Call.STATE_DISCONNECTING -> CallStatus.DISCONNECTING
        Call.STATE_DISCONNECTED -> CallStatus.DISCONNECTED
        Call.STATE_SELECT_PHONE_ACCOUNT -> CallStatus.SELECT_SIM
        else -> CallStatus.OTHER
    }

/** [Call.Details.getCallDirection] to [Direction], `null` when Telecom doesn't know. */
internal fun directionOf(direction: Int): Direction? =
    when (direction) {
        Call.Details.DIRECTION_INCOMING -> Direction.INCOMING
        Call.Details.DIRECTION_OUTGOING -> Direction.OUTGOING
        else -> null
    }

private val CAPABILITIES =
    mapOf(
        Call.Details.CAPABILITY_HOLD to CallCapability.HOLD,
        Call.Details.CAPABILITY_MUTE to CallCapability.MUTE,
        Call.Details.CAPABILITY_MERGE_CONFERENCE to CallCapability.MERGE_CONFERENCE,
        Call.Details.CAPABILITY_SWAP_CONFERENCE to CallCapability.SWAP_CONFERENCE,
        Call.Details.CAPABILITY_RESPOND_VIA_TEXT to CallCapability.RESPOND_VIA_TEXT,
    )

/** The capabilities in a [Call.Details.getCallCapabilities] mask. */
internal fun capabilitiesOf(mask: Int): Set<CallCapability> = CAPABILITIES.filterKeys { Call.Details.can(mask, it) }.values.toSet()

private fun capabilitiesOf(details: Call.Details): Set<CallCapability> = capabilitiesOf(details.callCapabilities)
