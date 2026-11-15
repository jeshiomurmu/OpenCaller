package io.github.jeshiomurmu.opencaller.feature.scanner

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.recording.VoiceScans
import io.github.jeshiomurmu.opencaller.core.model.RecordingLine
import io.github.jeshiomurmu.opencaller.core.model.RecordingSessionState
import io.github.jeshiomurmu.opencaller.core.telephony.CallPlacer
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallController
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallScanner
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStateRepository
import io.github.jeshiomurmu.opencaller.core.telephony.incall.LineConnection
import io.github.jeshiomurmu.opencaller.core.telephony.incall.LinePlan
import io.github.jeshiomurmu.opencaller.core.telephony.incall.RecordingFailure
import io.github.jeshiomurmu.opencaller.core.telephony.incall.VoiceScanStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans the other party's voice through the recording line (docs/prd.md F-PR-13, ADR 0011): asks the server for a
 * scan session (the line and a one-time code), dials the line with the code as tones, merges the calls once the
 * line's scanner has the code, and mutes the user's microphone so the scanner hears only the other party. Both
 * parties hear the scanner's beep. The scanner hangs up after a few seconds; then the microphone is unmuted (unless
 * the user had muted it) and the result is fetched. Nothing about the call is stored on the phone.
 */
@Singleton
class LineScanner
    @Inject
    constructor(
        private val calls: CallStateRepository,
        private val controller: CallController,
        placer: CallPlacer,
        private val scans: VoiceScans,
        dispatchers: DispatcherProvider,
    ) : CallScanner {
        private val line = LineConnection(calls, controller, placer)
        private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
        private val mutableStatus = MutableStateFlow<VoiceScanStatus>(VoiceScanStatus.Idle)
        private var job: Job? = null
        private var lineCallId: String? = null

        override val status: StateFlow<VoiceScanStatus> = mutableStatus.asStateFlow()

        override fun start(callId: String) {
            if (job?.isActive == true) return
            job = scope.launch { scan(callId) }
        }

        override fun stop() {
            lineCallId?.let(line::hangUp)
        }

        override fun dismiss() {
            when (mutableStatus.value) {
                is VoiceScanStatus.Done, VoiceScanStatus.NoResult, is VoiceScanStatus.Failed -> mutableStatus.value = VoiceScanStatus.Idle
                else -> Unit
            }
        }

        private suspend fun scan(callId: String) {
            mutableStatus.value = VoiceScanStatus.Connecting
            val started = if (line.activeCall(callId) == null) null else scans.startScan()
            mutableStatus.value =
                when (started) {
                    null -> failed(RecordingFailure.MERGE_FAILED)
                    is AppResult.Failure -> failed(LinePlan.failureOf(started.error))
                    is AppResult.Success -> onLine(callId, started.value)
                }
        }

        // Dial the line with the code, merge once its scanner has the code, listen, then fetch the result.
        private suspend fun onLine(
            callId: String,
            session: RecordingLine,
        ): VoiceScanStatus {
            val lineCall = line.dial(session) ?: return failed(RecordingFailure.CANNOT_DIAL)
            lineCallId = lineCall
            mutableStatus.value = VoiceScanStatus.Merging
            val agentReady = line.waitForAgent(lineCall) { stateOf(session.sessionId) }
            val watched = if (agentReady) line.merge(lineCall, callId) else null
            return if (watched == null) {
                line.hangUp(lineCall)
                failed(if (agentReady) RecordingFailure.MERGE_FAILED else RecordingFailure.LINE_NO_ANSWER)
            } else {
                listen(lineCall, watched)
                lineCallId = null
                result(session.sessionId)
            }
        }

        // The scanner hears the merged call; the user's microphone stays muted until the line hangs up.
        private suspend fun listen(
            lineCall: String,
            watched: String,
        ) {
            val wasMuted = calls.state.value.audio.muted
            controller.setMuted(true)
            mutableStatus.value = VoiceScanStatus.Listening
            try {
                withTimeoutOrNull(LISTEN_MILLIS) { line.awaitEnd(lineCall, watched) } ?: line.hangUp(lineCall)
            } finally {
                if (!wasMuted) controller.setMuted(false)
            }
        }

        // The scanner reports just before it hangs up; allow it a few seconds more.
        private suspend fun result(sessionId: String): VoiceScanStatus {
            var outcome: VoiceScanStatus? = null
            var checks = 0
            while (outcome == null && checks < RESULT_CHECKS) {
                if (checks > 0) delay(LineConnection.CHECK_MILLIS)
                val session = (scans.scan(sessionId) as? AppResult.Success)?.value
                outcome =
                    when (session?.state) {
                        RecordingSessionState.ENDED -> session.result?.let(VoiceScanStatus::Done) ?: VoiceScanStatus.NoResult
                        RecordingSessionState.FAILED -> VoiceScanStatus.NoResult
                        else -> null
                    }
                checks++
            }
            if (outcome == null) Timber.i("Voice scan: no result in time")
            return outcome ?: VoiceScanStatus.NoResult
        }

        private suspend fun stateOf(sessionId: String): RecordingSessionState? = (scans.scan(sessionId) as? AppResult.Success)?.value?.state

        private fun failed(reason: RecordingFailure): VoiceScanStatus {
            lineCallId = null
            return VoiceScanStatus.Failed(reason)
        }

        /** Timing. */
        companion object {
            /** The longest the user stays muted: the scanner listens at most 40 s after a 4 s merge delay. */
            const val LISTEN_MILLIS = 90_000L

            /** Checks for the result after the line hung up, a second apart. */
            const val RESULT_CHECKS = 10
        }
    }
