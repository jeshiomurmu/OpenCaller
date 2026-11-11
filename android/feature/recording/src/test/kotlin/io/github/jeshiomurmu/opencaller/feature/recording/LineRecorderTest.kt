package io.github.jeshiomurmu.opencaller.feature.recording

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.RecordingSession
import io.github.jeshiomurmu.opencaller.core.model.RecordingSessionState
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallRecordingStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.ConferenceAction
import io.github.jeshiomurmu.opencaller.core.telephony.incall.InCallState
import io.github.jeshiomurmu.opencaller.core.telephony.incall.RecordingFailure
import io.github.jeshiomurmu.opencaller.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The recorder's flow (TC-REC-001 on the phone's side): line, code, merge, watch, save. */
@OptIn(ExperimentalCoroutinesApi::class)
class LineRecorderTest {
    private val dispatcher = StandardTestDispatcher()
    private val telecom = FakeTelecom()
    private val recordings = FakeRecordings()
    private val recorder = LineRecorder(telecom, telecom, telecom, recordings, TestDispatcherProvider(dispatcher))

    private fun inCall() {
        telecom.calls.value = InCallState(listOf(call("1", CallStatus.ACTIVE, OTHER.e164, Direction.INCOMING)))
    }

    private fun TestScope.startAndAnswer() {
        recorder.start("1")
        runCurrent()
        assertEquals(CallRecordingStatus.Merging, recorder.status.value)
        telecom.lineAnswers()
        runCurrent()
    }

    @Test
    fun tcRec001TheLineIsDialledWithTheCodeMergedAndTheRecordingSaved() =
        runTest(dispatcher) {
            inCall()
            recordings.sessionStates.clear()
            recordings.sessionStates.addAll(
                listOf(
                    RecordingSession(RecordingSessionState.PENDING),
                    RecordingSession(RecordingSessionState.ACTIVE),
                    RecordingSession(RecordingSessionState.ACTIVE),
                    RecordingSession(RecordingSessionState.ENDED, recordingId = "r-1"),
                ),
            )
            startAndAnswer()
            // Two pauses, then the code and # as tones; no number of the other party goes anywhere.
            assertEquals("place +8809638000000,,12345678#", telecom.commands.first())
            advanceTimeBy(LineRecorder.CHECK_MILLIS + 1)
            runCurrent()
            assertEquals("perform ${ConferenceAction.Merge("2", "1")}", telecom.commands.last())
            assertEquals(CallRecordingStatus.Recording("1", "2"), recorder.status.value)

            // The line hangs up when the call ends: recording stops and the file is saved, labelled on the phone.
            telecom.update { it.copy(status = CallStatus.DISCONNECTED) }
            advanceUntilIdle()
            assertEquals(CallRecordingStatus.Idle, recorder.status.value)
            assertEquals(listOf("r-1" to OTHER), recordings.savedCalls)
        }

    @Test
    fun stopHangsUpTheLine() =
        runTest(dispatcher) {
            inCall()
            recordings.sessionStates.clear()
            recordings.sessionStates.add(RecordingSession(RecordingSessionState.ACTIVE))
            startAndAnswer()
            assertTrue(recorder.status.value is CallRecordingStatus.Recording)
            recorder.stop()
            runCurrent()
            assertTrue("hang up 2" in telecom.commands)
        }

    @Test
    fun theServersAnswerBecomesTheReason() =
        runTest(dispatcher) {
            inCall()
            recordings.line = AppResult.Failure(AppError.Validation("supporter_required"))
            recorder.start("1")
            advanceUntilIdle()
            assertEquals(CallRecordingStatus.Failed(RecordingFailure.SUPPORTER_REQUIRED), recorder.status.value)
            assertTrue(telecom.commands.isEmpty())
            recorder.dismiss()
            assertEquals(CallRecordingStatus.Idle, recorder.status.value)

            recordings.line = AppResult.Failure(AppError.Validation("recording_unavailable"))
            recorder.start("1")
            advanceUntilIdle()
            assertEquals(CallRecordingStatus.Failed(RecordingFailure.UNAVAILABLE), recorder.status.value)
        }

    @Test
    fun aLineThatNeverTakesTheCodeIsHungUp() =
        runTest(dispatcher) {
            inCall()
            startAndAnswer()
            advanceUntilIdle()
            assertEquals(CallRecordingStatus.Failed(RecordingFailure.LINE_NO_ANSWER), recorder.status.value)
            assertEquals("hang up 2", telecom.commands.last())
        }

    @Test
    fun callsThatCantBeMergedLeaveTheUsersCallAlone() =
        runTest(dispatcher) {
            inCall()
            telecom.mergeable = false
            recordings.sessionStates.clear()
            recordings.sessionStates.add(RecordingSession(RecordingSessionState.ACTIVE))
            startAndAnswer()
            advanceUntilIdle()
            assertEquals(CallRecordingStatus.Failed(RecordingFailure.MERGE_FAILED), recorder.status.value)
            assertEquals("hang up 2", telecom.commands.last())
            assertTrue(
                telecom.calls.value.calls
                    .first { it.id == "1" }
                    .isLive,
            )
        }

    @Test
    fun aRefusedDialOrNoActiveCallFails() =
        runTest(dispatcher) {
            recorder.start("1")
            advanceUntilIdle()
            assertEquals(CallRecordingStatus.Failed(RecordingFailure.MERGE_FAILED), recorder.status.value)

            inCall()
            telecom.placeResult = AppResult.Failure(AppError.PermissionDenied)
            recorder.start("1")
            advanceUntilIdle()
            assertEquals(CallRecordingStatus.Failed(RecordingFailure.CANNOT_DIAL), recorder.status.value)
        }
}
