package io.github.jeshiomurmu.opencaller.core.telephony.incall

import kotlinx.coroutines.flow.StateFlow

/** Why a recording didn't start or stopped early. */
enum class RecordingFailure {
    /** Recording isn't offered: no line on this server, or not approved in the user's country. */
    UNAVAILABLE,

    /** Recording is a supporter feature on this server. */
    SUPPORTER_REQUIRED,

    /** Not signed in, or the build has no server. */
    NOT_SIGNED_IN,

    /** The server couldn't be reached. */
    NETWORK,

    /** OpenCaller couldn't place the call to the line. */
    CANNOT_DIAL,

    /** The line didn't take the code in time. */
    LINE_NO_ANSWER,

    /** The calls couldn't be merged (the network or the phone doesn't offer it for these calls). */
    MERGE_FAILED,
}

/** Where a recording through the recording line stands, for the in-call screen. */
sealed interface CallRecordingStatus {
    /** Nothing is being recorded. */
    data object Idle : CallRecordingStatus

    /** Getting the line and calling it. */
    data object Connecting : CallRecordingStatus

    /** The line has the code; merging the calls. */
    data object Merging : CallRecordingStatus

    /** Recording: the call [callId] is merged with the line ([lineCallId]); both parties heard the beep. */
    data class Recording(
        val callId: String,
        val lineCallId: String,
    ) : CallRecordingStatus

    /** It didn't work, because of [reason]; the user's own call goes on. */
    data class Failed(
        val reason: RecordingFailure,
    ) : CallRecordingStatus
}

/**
 * Records a call through the recording line (docs/prd.md F-UT-03, ADR 0009): the in-call screen asks for it after
 * the user confirmed the consent reminder; `feature:recording` provides it. Hanging up the line stops recording.
 */
interface CallRecorder {
    /** The current recording, if any. */
    val status: StateFlow<CallRecordingStatus>

    /** Starts recording the active call [callId]. Does nothing while another recording is starting or running. */
    fun start(callId: String)

    /** Stops recording by hanging up the line (the user's call goes on, if the network separates it). */
    fun stop()

    /** Clears a [CallRecordingStatus.Failed] status once the screen showed it. */
    fun dismiss()
}
