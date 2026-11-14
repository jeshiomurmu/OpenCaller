package io.github.jeshiomurmu.opencaller.feature.recording

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.recording.CallRecordings
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RecordingLine
import io.github.jeshiomurmu.opencaller.core.model.RecordingSessionState
import io.github.jeshiomurmu.opencaller.core.telephony.CallPlacer
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallController
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallRecorder
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallRecordingStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStateRepository
import io.github.jeshiomurmu.opencaller.core.telephony.incall.LineConnection
import io.github.jeshiomurmu.opencaller.core.telephony.incall.LinePlan
import io.github.jeshiomurmu.opencaller.core.telephony.incall.RecordingFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
class LineRecorder
    @Inject
    constructor(
        calls: CallStateRepository,
        controller: CallController,
        placer: CallPlacer,
        private val recordings: CallRecordings,
        dispatchers: DispatcherProvider,
    ) : CallRecorder {
        private val line = LineConnection(calls, controller, placer)
        private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
        private val mutableStatus = MutableStateFlow<CallRecordingStatus>(CallRecordingStatus.Idle)
        private var job: Job? = null
        private var lineCallId: String? = null

        override val status: StateFlow<CallRecordingStatus> = mutableStatus.asStateFlow()

        override fun start(callId: String) {
            if (job?.isActive == true) return
            job = scope.launch { record(callId) }
        }

        override fun stop() {
            lineCallId?.let(line::hangUp)
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
            val call = line.activeCall(callId) ?: return Attempt.Failed(RecordingFailure.MERGE_FAILED)
            val otherParty = call.handle?.takeIf(PhoneNumber::isE164)?.let(::PhoneNumber)
            return when (val result = recordings.startLine()) {
                is AppResult.Failure -> {
                    Attempt.Failed(LinePlan.failureOf(result.error))
                }

                is AppResult.Success -> {
                    line.dial(result.value)?.let { Attempt.Dialled(callId, otherParty, result.value, it) }
                        ?: Attempt.Failed(RecordingFailure.CANNOT_DIAL)
                }
            }
        }

        // The line is ringing or answered: wait for the code to be taken, merge, then record until the line hangs up.
        private suspend fun onLine(attempt: Attempt.Dialled) {
            val lineCall = attempt.lineCall
            lineCallId = lineCall
            mutableStatus.value = CallRecordingStatus.Merging
            val sessionId = attempt.line.sessionId
            if (!line.waitForAgent(lineCall) { (recordings.session(sessionId) as? AppResult.Success)?.value?.state }) {
                line.hangUp(lineCall)
                fail(RecordingFailure.LINE_NO_ANSWER)
                return
            }
            val watched = line.merge(lineCall, attempt.callId)
            if (watched == null) {
                line.hangUp(lineCall)
                fail(RecordingFailure.MERGE_FAILED)
                return
            }
            mutableStatus.value = CallRecordingStatus.Recording(attempt.callId, lineCall)
            line.awaitEnd(lineCall, watched)
            lineCallId = null
            mutableStatus.value = CallRecordingStatus.Idle
            save(sessionId, attempt.otherParty)
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
            /** How often the server is asked about the session (the line's steps are [LineConnection]'s). */
            const val CHECK_MILLIS = LineConnection.CHECK_MILLIS

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
