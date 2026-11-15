package io.github.jeshiomurmu.opencaller.core.telephony.incall

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.RecordingLine
import io.github.jeshiomurmu.opencaller.core.model.RecordingSessionState
import io.github.jeshiomurmu.opencaller.core.telephony.CallPlacer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's side of a call to the recording line, shared by the recorder (ADR 0009) and the voice scan (ADR 0011):
 * dial the line with the one-time code as tones, wait until the server says the line's agent has the code, merge the
 * line into the user's call and watch it. Holds no state; the caller decides what each step's failure means.
 */
class LineConnection(
    private val calls: CallStateRepository,
    private val controller: CallController,
    private val placer: CallPlacer,
) {
    /** The user's call [callId] if it is active (the only state a line can be merged into). */
    fun activeCall(callId: String): TrackedCall? =
        calls.state.value
            .call(callId)
            ?.takeIf { it.status == CallStatus.ACTIVE }

    /** Places the call to [line] (Telecom holds the user's call) and returns its id once Telecom shows it. */
    suspend fun dial(line: RecordingLine): String? {
        val before =
            calls.state.value.calls
                .map(TrackedCall::id)
                .toSet()
        if (placer.place(LinePlan.dialString(line)) is AppResult.Failure) return null
        return withTimeoutOrNull(DIAL_TIMEOUT_MILLIS) {
            calls.state
                .map { state ->
                    state.calls.firstOrNull {
                        it.id !in before && it.isLive && it.direction != Direction.INCOMING && LinePlan.isLine(it.handle, line.number)
                    }
                }.first { it != null }
                ?.id
        }
    }

    /**
     * True once the server reports the session [ACTIVE][RecordingSessionState.ACTIVE] (the line's agent claimed it
     * with the code); false when the line call ends or [CODE_CHECKS] checks pass first. [sessionState] asks the
     * server (null when it couldn't answer).
     */
    suspend fun waitForAgent(
        lineCall: String,
        sessionState: suspend () -> RecordingSessionState?,
    ): Boolean {
        var state = RecordingSessionState.PENDING
        var checks = 0
        while (state == RecordingSessionState.PENDING && checks < CODE_CHECKS && isLive(lineCall)) {
            if (checks > 0) delay(CHECK_MILLIS)
            state = sessionState() ?: RecordingSessionState.PENDING
            checks++
        }
        return state == RecordingSessionState.ACTIVE
    }

    /** Merges the line into the user's call; returns the call to watch (the line, or the conference it joined). */
    suspend fun merge(
        lineCall: String,
        callId: String,
    ): String? {
        val action =
            withTimeoutOrNull(MERGE_WAIT_MILLIS) {
                calls.state.map { LinePlan.mergeOf(it, lineCall, callId) }.first { it != null }
            } ?: return null
        controller.perform(action)
        val merged =
            withTimeoutOrNull(MERGE_WAIT_MILLIS) {
                calls.state.first { state ->
                    val line = state.call(lineCall)
                    line == null || !line.isLive || line.parentId != null || line.isConference
                }
            }
        return merged?.call(lineCall)?.takeIf { it.isLive }?.let { it.parentId ?: lineCall }
    }

    /** Suspends until the line call or the call it is [watched] through ends. */
    suspend fun awaitEnd(
        lineCall: String,
        watched: String,
    ) {
        calls.state.first { state -> state.call(lineCall)?.isLive != true || state.call(watched)?.isLive != true }
    }

    /** True while the call [callId] is live. */
    fun isLive(callId: String): Boolean =
        calls.state.value
            .call(callId)
            ?.isLive == true

    /** Hangs up the line call (the user's call goes on, if the network separates it). */
    fun hangUp(lineCall: String) = controller.hangUp(lineCall)

    /** Timing. */
    companion object {
        /** How long Telecom may take to show the call to the line. */
        const val DIAL_TIMEOUT_MILLIS = 15_000L

        /** How often the server is asked about the session. */
        const val CHECK_MILLIS = 1_000L

        /** Checks before giving up on the line taking the code (the line answers, pauses, tones: ~10 s). */
        const val CODE_CHECKS = 45

        /** How long the calls may take to become mergeable, and to merge. */
        const val MERGE_WAIT_MILLIS = 10_000L
    }
}
