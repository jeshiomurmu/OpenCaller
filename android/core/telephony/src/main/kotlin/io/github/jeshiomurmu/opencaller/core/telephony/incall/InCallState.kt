package io.github.jeshiomurmu.opencaller.core.telephony.incall

import io.github.jeshiomurmu.opencaller.core.model.Direction

/** A call's state as Telecom reports it (`Call.Details.getState`), reduced to what the in-call screen shows. */
enum class CallStatus {
    /** Being set up (new, connecting or being pulled from another device). */
    CONNECTING,

    /** Outgoing: the other phone is being called. */
    DIALING,

    /** Incoming and not answered yet. */
    RINGING,

    /** Talking. */
    ACTIVE,

    /** On hold. */
    HOLDING,

    /** Hanging up. */
    DISCONNECTING,

    /** Over. */
    DISCONNECTED,

    /** Outgoing and waiting for the user to choose a SIM. */
    SELECT_SIM,

    /** Anything else Telecom may report (for example, audio processing by a screening app). */
    OTHER,
}

/** What the user can do with a call right now (`Call.Details` capabilities). */
enum class CallCapability {
    /** Put on hold or take off hold. */
    HOLD,

    /** Mute the microphone. */
    MUTE,

    /** Merge the calls of a conference. */
    MERGE_CONFERENCE,

    /** Swap the calls of a conference. */
    SWAP_CONFERENCE,

    /** Decline with a text message. */
    RESPOND_VIA_TEXT,
}

/** A SIM (phone account) the user can place a call with. [label] is `null` when its name can't be read. */
data class SimChoice(
    val id: String,
    val label: String?,
)

/**
 * One call known to Telecom.
 *
 * @property id OpenCaller's own id for the call, stable while the call exists.
 * @property handle the number part of the call's `tel:` address, or `null`.
 * @property hidden true when the caller withheld their number.
 * @property connectedAtMillis when the call connected (wall clock), or 0 before that.
 * @property childIds the calls inside this conference.
 * @property conferenceableIds calls this one can be merged with.
 * @property cannedReplies the system's quick replies for declining with a message (may arrive after the call).
 * @property simChoices the SIMs to choose from while [status] is [CallStatus.SELECT_SIM].
 */
data class TrackedCall(
    val id: String,
    val handle: String?,
    val hidden: Boolean,
    val status: CallStatus,
    val direction: Direction?,
    val capabilities: Set<CallCapability> = emptySet(),
    val isConference: Boolean = false,
    val parentId: String? = null,
    val childIds: List<String> = emptyList(),
    val conferenceableIds: List<String> = emptyList(),
    val connectedAtMillis: Long = 0,
    val cannedReplies: List<String> = emptyList(),
    val simChoices: List<SimChoice> = emptyList(),
) {
    /** True when [capability] is available. */
    fun can(capability: CallCapability): Boolean = capability in capabilities

    /** True while the call still exists for the user. */
    val isLive: Boolean get() = status != CallStatus.DISCONNECTED
}

/** Where call audio can go. */
enum class AudioRouteType {
    /** The phone's earpiece. */
    EARPIECE,

    /** The loudspeaker. */
    SPEAKER,

    /** A Bluetooth headset or car kit. */
    BLUETOOTH,

    /** A wired headset. */
    WIRED_HEADSET,

    /** Another device the call is streamed to. */
    STREAMING,

    /** Unknown. */
    UNKNOWN,
}

/**
 * One place call audio can go. [name] is the device's name where Android gives it (Bluetooth on Android 14+).
 */
data class AudioRoute(
    val id: String,
    val type: AudioRouteType,
    val name: String? = null,
)

/** Call audio: where it goes now, where it could go, and the microphone. */
data class CallAudio(
    val current: AudioRoute? = null,
    val available: List<AudioRoute> = emptyList(),
    val muted: Boolean = false,
) {
    /** True when there is more than earpiece and speaker to choose from (a headset is connected). */
    val hasExternalRoutes: Boolean get() = available.any { it.type != AudioRouteType.EARPIECE && it.type != AudioRouteType.SPEAKER }
}

/** Every call Telecom has given the in-call screen, plus the audio state. */
data class InCallState(
    val calls: List<TrackedCall> = emptyList(),
    val audio: CallAudio = CallAudio(),
    val canAddCall: Boolean = false,
) {
    /** Calls the user sees as separate rows: everything that is not inside a conference. */
    val topLevel: List<TrackedCall> get() = calls.filter { it.parentId == null && it.isLive }

    /** The call waiting to be answered, if any. */
    val ringing: TrackedCall? get() = topLevel.firstOrNull { it.status == CallStatus.RINGING }

    /** The call the controls act on: the one in progress, else one being set up, else one on hold. */
    val primary: TrackedCall?
        get() = FOREGROUND_ORDER.firstNotNullOfOrNull { status -> topLevel.firstOrNull { it.status == status } }

    /** Another call on hold behind [primary], if any. */
    val held: TrackedCall? get() = topLevel.firstOrNull { it.status == CallStatus.HOLDING && it.id != primary?.id }

    /** True when no call is left. */
    val isIdle: Boolean get() = calls.none { it.isLive }

    /** The call with [id], if it still exists. */
    fun call(id: String): TrackedCall? = calls.firstOrNull { it.id == id }

    private companion object {
        val FOREGROUND_ORDER =
            listOf(
                CallStatus.ACTIVE,
                CallStatus.DIALING,
                CallStatus.CONNECTING,
                CallStatus.SELECT_SIM,
                CallStatus.OTHER,
                CallStatus.HOLDING,
                CallStatus.DISCONNECTING,
            )
    }
}
