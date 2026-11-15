package io.github.jeshiomurmu.opencaller.core.telephony.incall

import io.github.jeshiomurmu.opencaller.core.model.VoiceScanResult
import kotlinx.coroutines.flow.StateFlow

/** Where a voice scan through the recording line stands, for the in-call screen. */
sealed interface VoiceScanStatus {
    /** No scan. */
    data object Idle : VoiceScanStatus

    /** Getting the line and calling it. */
    data object Connecting : VoiceScanStatus

    /** The line has the code; merging the calls. */
    data object Merging : VoiceScanStatus

    /** The scanner listens to the other party; the user's microphone is muted until it is done. */
    data object Listening : VoiceScanStatus

    /** The scan finished with [result]. */
    data class Done(
        val result: VoiceScanResult,
    ) : VoiceScanStatus

    /** The scanner heard too little, or couldn't score what it heard. */
    data object NoResult : VoiceScanStatus

    /** The line couldn't be reached or merged, because of [reason]; the user's own call goes on. */
    data class Failed(
        val reason: RecordingFailure,
    ) : VoiceScanStatus
}

/**
 * Scans the other party's voice for signs of a generated or cloned voice (docs/prd.md F-PR-13, ADR 0011), through
 * the recording line: the line's scanner listens for a few seconds and hangs up. `feature:scanner` provides it.
 */
interface CallScanner {
    /** The current scan, if any. */
    val status: StateFlow<VoiceScanStatus>

    /** Scans the active call [callId]. Does nothing while a scan or a recording runs. */
    fun start(callId: String)

    /** Stops listening (hangs up the line) and unmutes. */
    fun stop()

    /** Clears a result, [VoiceScanStatus.NoResult] or [VoiceScanStatus.Failed] once the user closed it. */
    fun dismiss()
}
