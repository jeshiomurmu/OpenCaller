package io.github.jeshiomurmu.opencaller.feature.scanner

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.RecordingSessionState
import io.github.jeshiomurmu.opencaller.core.model.ScamCue
import io.github.jeshiomurmu.opencaller.core.model.VoiceScanResult
import io.github.jeshiomurmu.opencaller.core.model.VoiceScanSession
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallAudio
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.ConferenceAction
import io.github.jeshiomurmu.opencaller.core.telephony.incall.InCallState
import io.github.jeshiomurmu.opencaller.core.telephony.incall.LineConnection
import io.github.jeshiomurmu.opencaller.core.telephony.incall.RecordingFailure
import io.github.jeshiomurmu.opencaller.core.telephony.incall.VoiceScanStatus
import io.github.jeshiomurmu.opencaller.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The voice scan on the phone's side (TC-SCAN-001): line, code, merge, muted while the scanner listens, result. */
@OptIn(ExperimentalCoroutinesApi::class)
class LineScannerTest {
    private val dispatcher = StandardTestDispatcher()
    private val telecom = FakeTelecom()
    private val scans = FakeScans()
    private val scanner = LineScanner(telecom, telecom, telecom, scans, TestDispatcherProvider(dispatcher))

    private fun inCall(muted: Boolean = false) {
        telecom.calls.value =
            InCallState(listOf(call("1", CallStatus.ACTIVE, OTHER.e164, Direction.INCOMING)), audio = CallAudio(muted = muted))
    }

    private fun answered(vararg states: VoiceScanSession) {
        scans.states.clear()
        scans.states.addAll(states)
    }

    private fun TestScope.startAndAnswer() {
        scanner.start("1")
        runCurrent()
        assertEquals(VoiceScanStatus.Merging, scanner.status.value)
        telecom.lineAnswers()
        runCurrent()
    }

    @Test
    fun tcScan001TheLineIsMergedTheUserMutedAndTheResultShown() =
        runTest(dispatcher) {
            inCall()
            val result = VoiceScanResult(0.83f, listOf(ScamCue.OTP_REQUEST, ScamCue.URGENCY))
            answered(
                VoiceScanSession(RecordingSessionState.PENDING),
                VoiceScanSession(RecordingSessionState.ACTIVE),
                VoiceScanSession(RecordingSessionState.ACTIVE),
                VoiceScanSession(RecordingSessionState.ENDED, result),
            )
            startAndAnswer()
            assertEquals("place +8809638000000,,12345678#", telecom.commands.first()) // no number of the other party
            advanceTimeBy(LineConnection.CHECK_MILLIS + 1)
            runCurrent()
            assertEquals(VoiceScanStatus.Listening, scanner.status.value)
            assertTrue("perform ${ConferenceAction.Merge("2", "1")}" in telecom.commands)
            assertEquals("mute true", telecom.commands.last()) // the scanner hears only the other party
            assertTrue(telecom.calls.value.audio.muted)

            // The scanner hangs up when it is done: the user is unmuted and sees the result.
            telecom.update { if (it.id == "2") it.copy(status = CallStatus.DISCONNECTED) else it }
            advanceUntilIdle()
            assertFalse(telecom.calls.value.audio.muted)
            assertEquals(VoiceScanStatus.Done(result), scanner.status.value)
            scanner.dismiss()
            assertEquals(VoiceScanStatus.Idle, scanner.status.value)
        }

    @Test
    fun aUserWhoHadMutedStaysMutedAndAFailedScanShowsNoResult() =
        runTest(dispatcher) {
            inCall(muted = true)
            answered(VoiceScanSession(RecordingSessionState.ACTIVE), VoiceScanSession(RecordingSessionState.FAILED))
            startAndAnswer()
            runCurrent()
            assertEquals(VoiceScanStatus.Listening, scanner.status.value)
            scanner.stop() // hangs up the line
            advanceUntilIdle()
            assertTrue("hang up 2" in telecom.commands)
            assertTrue(telecom.calls.value.audio.muted)
            assertFalse("mute false" in telecom.commands)
            assertEquals(VoiceScanStatus.NoResult, scanner.status.value)
        }

    @Test
    fun aScannerThatNeverHangsUpIsHungUpAndTheUserUnmuted() =
        runTest(dispatcher) {
            inCall()
            answered(VoiceScanSession(RecordingSessionState.ACTIVE))
            startAndAnswer()
            advanceUntilIdle()
            assertTrue("hang up 2" in telecom.commands)
            assertFalse(telecom.calls.value.audio.muted)
            assertEquals(VoiceScanStatus.NoResult, scanner.status.value) // still active after the checks
        }

    @Test
    fun theServersRefusalAndTheLinesProblemsBecomeTheReason() =
        runTest(dispatcher) {
            inCall()
            scans.line = AppResult.Failure(AppError.Validation("scanner_unavailable"))
            scanner.start("1")
            advanceUntilIdle()
            assertEquals(VoiceScanStatus.Failed(RecordingFailure.UNAVAILABLE), scanner.status.value)
            assertTrue(telecom.commands.isEmpty())

            scans.line = AppResult.Success(LINE)
            scanner.start("1")
            runCurrent()
            telecom.lineAnswers()
            advanceUntilIdle() // the code is never taken
            assertEquals(VoiceScanStatus.Failed(RecordingFailure.LINE_NO_ANSWER), scanner.status.value)
            assertEquals("hang up 2", telecom.commands.last())
            assertFalse(telecom.calls.value.audio.muted)
        }

    @Test
    fun callsThatCantBeMergedAreLeftAloneAndUnmuted() =
        runTest(dispatcher) {
            inCall()
            telecom.mergeable = false
            answered(VoiceScanSession(RecordingSessionState.ACTIVE))
            startAndAnswer()
            advanceUntilIdle()
            assertEquals(VoiceScanStatus.Failed(RecordingFailure.MERGE_FAILED), scanner.status.value)
            assertFalse("mute true" in telecom.commands)
        }
}
