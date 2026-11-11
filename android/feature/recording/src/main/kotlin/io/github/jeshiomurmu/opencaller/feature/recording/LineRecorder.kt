package io.github.jeshiomurmu.opencaller.feature.recording

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.recording.CallRecordings
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RecordingLine
import io.github.jeshiomurmu.opencaller.core.model.RecordingSessionState
import io.github.jeshiomurmu.opencaller.core.telephony.CallPlacer
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallController
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallRecorder
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallRecordingStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStateRepository
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.RecordingFailure
import io.github.jeshiomurmu.opencaller.core.telephony.incall.TrackedCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records a call through the recording line (docs/prd.md F-UT-03, ADR 0009): asks the server for the line and a
 * one-time code, dials the line with the code as tones (Telecom holds the user's call), waits until the server says
 * the line has the code, merges the two calls (`Call.conference`), and watches the line until it hangs up. Both
 * parties hear the line's beep and announcement. Afterwards the recording is saved on the phone, labelled with the
 * other party's number (which never leaves the phone). Runs on the main thread, like the in-call service.
 */
@Singleton
@Suppress("TooManyFunctions") // CallRecorder's three commands plus one function per step of the flow.
class LineRecorder
    @Inject
    constructor(
        private val calls: CallStateRepository,
        private val controller: CallController,
        private val placer: CallPlacer,
        private val recordings: CallRecordings,
        dispatchers: DispatcherProvider,
    ) : CallRecorder {
        private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
        private val mutableStatus = MutableStateFlow<CallRecordingStatus>(CallRecordingStatus.Idle)
        private var job: Job? = null
        private var lineCallId: String? = null
        private var mergeTried = false

        override val status: StateFlow<CallRecordingStatus> = mutableStatus.asStateFlow()

        override fun start(callId: String) {
            if (job?.isActive == true) return
            job = scope.launch { record(callId) }
        }

        override fun stop() {
            lineCallId?.let(controller::hangUp)
        }

        override fun dismiss() {
            if (mutableStatus.value is CallRecordingStatus.Failed) mutableStatus.value = CallRecordingStatus.Idle
        }

        private suspend fun record(callId: String) {
            mutableStatus.value = CallRecordingStatus.Connecting
            when (val attempt = connect(callId)) {
                is Attempt.Failed -> fail(attempt.reason)
                is Attempt.Dialled -> onLine(attempt)
            }
        }

        // The user's call must be active; then the line and its code, and the call to the line.
        private suspend fun connect(callId: String): Attempt {
            val call =
                calls.state.value
                    .call(callId)
                    ?.takeIf { it.status == CallStatus.ACTIVE } ?: return Attempt.Failed(RecordingFailure.MERGE_FAILED)
            val otherParty = call.handle?.takeIf(PhoneNumber::isE164)?.let(::PhoneNumber)
            return when (val result = recordings.startLine()) {
                is AppResult.Failure -> {
                    Attempt.Failed(LinePlan.failureOf(result.error))
                }

                is AppResult.Success -> {
                    dial(result.value)?.let { Attempt.Dialled(callId, otherParty, result.value, it) }
                        ?: Attempt.Failed(RecordingFailure.CANNOT_DIAL)
                }
            }
        }

        // The line is ringing or answered: wait for the code to be taken, merge, then record until the line hangs up.
        private suspend fun onLine(attempt: Attempt.Dialled) {
            val lineCall = attempt.lineCall
            lineCallId = lineCall
            mutableStatus.value = CallRecordingStatus.Merging
            val watched = if (lineHasCode(attempt.line.sessionId, lineCall)) merge(lineCall, attempt.callId) else null
            if (watched == null) {
                controller.hangUp(lineCall)
                // The line took the code if the merge was tried; otherwise it never did.
                fail(if (mergeTried) RecordingFailure.MERGE_FAILED else RecordingFailure.LINE_NO_ANSWER)
                return
            }
            mutableStatus.value = CallRecordingStatus.Recording(attempt.callId, lineCall)
            calls.state.first { state -> state.call(lineCall)?.isLive != true || state.call(watched)?.isLive != true }
            lineCallId = null
            mutableStatus.value = CallRecordingStatus.Idle
            save(attempt.line.sessionId, attempt.otherParty)
        }

        // Places the call to the line and finds it among Telecom's calls.
        private suspend fun dial(line: RecordingLine): String? {
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

        // The server knows when the line's agent has claimed the session with the code; the line call must stay up.
        private suspend fun lineHasCode(
            sessionId: String,
            lineCall: String,
        ): Boolean {
            mergeTried = false
            var state = RecordingSessionState.PENDING
            var checks = 0
            while (state == RecordingSessionState.PENDING && checks < CODE_CHECKS && isLive(lineCall)) {
                if (checks > 0) delay(CHECK_MILLIS)
                state = (recordings.session(sessionId) as? AppResult.Success)?.value?.state ?: RecordingSessionState.PENDING
                checks++
            }
            return state == RecordingSessionState.ACTIVE
        }

        private fun isLive(callId: String): Boolean =
            calls.state.value
                .call(callId)
                ?.isLive == true

        // Merges the line into the user's call; returns the call to watch (the line, or the conference it joined).
        private suspend fun merge(
            lineCall: String,
            callId: String,
        ): String? {
            mergeTried = true
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

        private suspend fun save(
            sessionId: String,
            otherParty: PhoneNumber?,
        ) {
            repeat(SAVE_CHECKS) {
                val session = (recordings.session(sessionId) as? AppResult.Success)?.value
                when (session?.state) {
                    RecordingSessionState.ENDED -> {
                        session.recordingId?.let { recordings.save(it, otherParty) }
                        return
                    }

                    RecordingSessionState.FAILED -> {
                        return
                    }

                    else -> {
                        delay(SAVE_CHECK_MILLIS)
                    }
                }
            }
            // The push (or the next sync) saves it later.
            Timber.i("Recording not ready yet; it is saved when the server announces it")
        }

        private fun fail(reason: RecordingFailure) {
            lineCallId = null
            mutableStatus.value = CallRecordingStatus.Failed(reason)
        }

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

            private const val SAVE_CHECKS = 40
            private const val SAVE_CHECK_MILLIS = 3_000L
        }
    }

/** The first steps' outcome: the line is being called, or why not. */
private sealed interface Attempt {
    data class Dialled(
        val callId: String,
        val otherParty: PhoneNumber?,
        val line: RecordingLine,
        val lineCall: String,
    ) : Attempt

    data class Failed(
        val reason: RecordingFailure,
    ) : Attempt
}
