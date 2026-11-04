package io.github.jeshiomurmu.opencaller.feature.voip

import io.github.jeshiomurmu.opencaller.core.model.AppCallState

/** Where an app call is on this phone. */
enum class AppCallPhase {
    /** Asking the server to call (outgoing). */
    CONNECTING,

    /** The callee's phone rings; the caller waits in the room. */
    RINGING_OUT,

    /** This phone rings. */
    RINGING_IN,

    /** Answered here; joining the room. */
    ANSWERING,

    /** Both parties are in the room. */
    ACTIVE,

    /** Over; [AppCallUi.end] says why. */
    ENDED,
}

/** Why an app call ended, for the last line on the screen. */
enum class AppCallEnd {
    HUNG_UP,
    DECLINED,
    MISSED,
    UNREACHABLE,
    FAILED,
    BUSY,
}

/**
 * The app call on this phone, as the screen and the notifications show it. [number], [name] and [clipPath]
 * describe the other party; [connectedAtMillis] starts the call timer.
 */
data class AppCallUi(
    val phase: AppCallPhase,
    val incoming: Boolean,
    val number: String?,
    val name: String? = null,
    val inviteId: String? = null,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val connectedAtMillis: Long? = null,
    val clipPath: String? = null,
    val end: AppCallEnd? = null,
) {
    /** True while the call can still be hung up or answered. */
    val isLive: Boolean get() = phase != AppCallPhase.ENDED

    /** The call after the server reported [state] for it. */
    fun afterServer(state: AppCallState): AppCallUi =
        when (state) {
            AppCallState.RINGING, AppCallState.ACCEPTED -> this
            AppCallState.DECLINED -> ended(if (incoming) AppCallEnd.HUNG_UP else AppCallEnd.DECLINED)
            AppCallState.MISSED -> ended(AppCallEnd.MISSED)
            AppCallState.CANCELLED, AppCallState.ENDED -> ended(AppCallEnd.HUNG_UP)
        }

    /** The call once both parties are in the room at [nowMillis]. */
    fun connected(nowMillis: Long): AppCallUi =
        if (isLive) {
            copy(
                phase = AppCallPhase.ACTIVE,
                connectedAtMillis =
                    connectedAtMillis ?: nowMillis,
            )
        } else {
            this
        }

    /** The call ended for [reason] (an ended call keeps its first reason). */
    fun ended(reason: AppCallEnd): AppCallUi = if (isLive) copy(phase = AppCallPhase.ENDED, end = reason) else this
}
