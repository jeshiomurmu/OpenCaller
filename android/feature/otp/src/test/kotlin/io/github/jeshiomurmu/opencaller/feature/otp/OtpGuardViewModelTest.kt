package io.github.jeshiomurmu.opencaller.feature.otp

import androidx.lifecycle.SavedStateHandle
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallController
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStateRepository
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.ConferenceAction
import io.github.jeshiomurmu.opencaller.core.telephony.incall.InCallState
import io.github.jeshiomurmu.opencaller.core.telephony.incall.TrackedCall
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Records the calls the warning hangs up; the other commands are not used by it. */
private class HangUpRecorder : CallController {
    val hungUp = mutableListOf<String>()

    override fun hangUp(callId: String) {
        hungUp += callId
    }

    override fun answer(callId: String) = Unit

    override fun decline(callId: String) = Unit

    override fun declineWithMessage(
        callId: String,
        message: String,
    ) = Unit

    override fun hold(callId: String) = Unit

    override fun resume(callId: String) = Unit

    override fun playTone(
        callId: String,
        digit: Char,
    ) = Unit

    override fun stopTone(callId: String) = Unit

    override fun perform(action: ConferenceAction) = Unit

    override fun setMuted(muted: Boolean) = Unit

    override fun route(routeId: String) = Unit

    override fun selectSim(
        callId: String,
        simId: String,
    ) = Unit

    override fun refresh() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class OtpGuardViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val state = MutableStateFlow(InCallState())
    private val calls =
        object : CallStateRepository {
            override val state: StateFlow<InCallState> = this@OtpGuardViewModelTest.state
        }
    private val controller = HangUpRecorder()

    private fun viewModel(
        from: String?,
        flashCall: Boolean,
    ) = OtpGuardViewModel(
        SavedStateHandle(mapOf(OtpGuardActivity.EXTRA_FROM to from, OtpGuardActivity.EXTRA_FLASH_CALL to flashCall)),
        calls,
        controller,
    )

    @Test
    fun tcOtp005TheWarningEndsTheCallWithTheStranger() =
        runTest {
            val vm = viewModel(from = "Ridely", flashCall = false)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            state.value =
                InCallState(
                    calls =
                        listOf(
                            TrackedCall("c1", "+12015550188", hidden = false, status = CallStatus.ACTIVE, direction = Direction.INCOMING),
                        ),
                )

            assertEquals("Ridely", vm.uiState.value.codeFrom)
            assertTrue(vm.uiState.value.canHangUp)
            vm.onEvent(OtpGuardEvent.HangUp)
            assertEquals(listOf("c1"), controller.hungUp)
        }

    @Test
    fun withoutACallOfOpenCallersThereIsNoHangUp() =
        runTest {
            val vm = viewModel(from = "Example Flash", flashCall = true)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            assertTrue(vm.uiState.value.flashCall)
            assertFalse("screening-only mode: the system's call screen ends the call", vm.uiState.value.canHangUp)
        }
}
