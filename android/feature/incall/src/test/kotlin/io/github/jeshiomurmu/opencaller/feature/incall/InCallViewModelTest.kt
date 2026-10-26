package io.github.jeshiomurmu.opencaller.feature.incall

import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.incall.AudioRoute
import io.github.jeshiomurmu.opencaller.core.telephony.incall.AudioRouteType
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallAudio
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallCapability
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallController
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStateRepository
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.ConferenceAction
import io.github.jeshiomurmu.opencaller.core.telephony.incall.InCallState
import io.github.jeshiomurmu.opencaller.core.telephony.incall.TrackedCall
import io.github.jeshiomurmu.opencaller.core.testing.FakeClock
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import io.github.jeshiomurmu.opencaller.feature.incall.calls.CallerDirectory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Records every command the in-call screen sends. */
class FakeCallController : CallController {
    val commands = mutableListOf<String>()

    override fun answer(callId: String) {
        commands += "answer $callId"
    }

    override fun decline(callId: String) {
        commands += "decline $callId"
    }

    override fun declineWithMessage(
        callId: String,
        message: String,
    ) {
        commands += "decline $callId: $message"
    }

    override fun hangUp(callId: String) {
        commands += "hang up $callId"
    }

    override fun hold(callId: String) {
        commands += "hold $callId"
    }

    override fun resume(callId: String) {
        commands += "resume $callId"
    }

    override fun playTone(
        callId: String,
        digit: Char,
    ) {
        commands += "tone $callId $digit"
    }

    override fun stopTone(callId: String) {
        commands += "stop tone $callId"
    }

    override fun perform(action: ConferenceAction) {
        commands += "perform $action"
    }

    override fun setMuted(muted: Boolean) {
        commands += "mute $muted"
    }

    override fun route(routeId: String) {
        commands += "route $routeId"
    }

    override fun selectSim(
        callId: String,
        simId: String,
    ) {
        commands += "sim $callId $simId"
    }

    override fun refresh() {
        commands += "refresh"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class InCallViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val number = PhoneNumber("+12015550123")
    private val calls = MutableStateFlow(InCallState())
    private val callers = MutableStateFlow<Map<String, CallerInfo>>(emptyMap())
    private val controller = FakeCallController()
    private val userReplies = MutableStateFlow<List<String>>(emptyList())
    private val vm by lazy {
        InCallViewModel(
            calls =
                object : CallStateRepository {
                    override val state: StateFlow<InCallState> = calls
                },
            callers =
                object : CallerDirectory {
                    override val callers: StateFlow<Map<String, CallerInfo>> = this@InCallViewModelTest.callers
                },
            numbers = CallNumbers(PhoneNumberNormalizer()) { "US" },
            controller = controller,
            templates = { userReplies },
            clock = FakeClock(),
        )
    }

    private fun TestScope.observe() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    private fun call(
        id: String,
        status: CallStatus,
        vararg capabilities: CallCapability,
        handle: String? = number.e164,
    ) = TrackedCall(
        id,
        handle,
        hidden = handle == null,
        status = status,
        direction = Direction.INCOMING,
        capabilities = capabilities.toSet(),
    )

    @Test
    fun ringingCallShowsItsCallerAndCanBeAnsweredOrDeclined() =
        runTest {
            assertFalse("nothing to close before the first state", InCallUiState().isOver)
            observe()
            calls.value = InCallState(listOf(call("1", CallStatus.RINGING)))
            callers.value = mapOf("1" to CallerInfo(number, displayName = "Alex", nameSource = NameSource.CONTACT))

            val ringing = vm.uiState.value.ringing
            assertEquals("Alex", ringing?.caller?.displayName)
            assertEquals("(201) 555-0123", ringing?.number)
            assertNull(vm.uiState.value.primary)

            vm.onEvent(InCallEvent.Answer("1"))
            vm.onEvent(InCallEvent.Decline("1"))
            vm.onEvent(InCallEvent.DeclineWithMessage("1", "On my way"))
            assertEquals(listOf("answer 1", "decline 1", "decline 1: On my way"), controller.commands)
        }

    @Test
    fun controlsActOnThePrimaryCall() =
        runTest {
            observe()
            calls.value =
                InCallState(
                    calls = listOf(call("1", CallStatus.ACTIVE, CallCapability.HOLD, CallCapability.MUTE)),
                    audio = CallAudio(muted = false, available = listOf(AudioRoute("s", AudioRouteType.SPEAKER))),
                )
            assertTrue(vm.uiState.value.canHold)
            assertTrue(vm.uiState.value.canMute)
            assertFalse(vm.uiState.value.usesEarpiece)

            vm.onEvent(InCallEvent.ToggleMute)
            vm.onEvent(InCallEvent.ToggleHold)
            vm.onEvent(InCallEvent.KeyDown('7'))
            vm.onEvent(InCallEvent.KeyUp)
            vm.onEvent(InCallEvent.Route("s"))
            vm.onEvent(InCallEvent.HangUp("1"))
            assertEquals(listOf("mute true", "hold 1", "tone 1 7", "stop tone 1", "route s", "hang up 1"), controller.commands)

            calls.value = InCallState(listOf(call("1", CallStatus.HOLDING, CallCapability.HOLD)))
            vm.onEvent(InCallEvent.ToggleHold)
            assertEquals("resume 1", controller.commands.last())
        }

    @Test
    fun secondCallOffersSwapAndMerge() =
        runTest {
            observe()
            calls.value =
                InCallState(
                    listOf(
                        call("1", CallStatus.ACTIVE, CallCapability.HOLD).copy(conferenceableIds = listOf("2")),
                        call("2", CallStatus.HOLDING, CallCapability.HOLD),
                    ),
                )
            val state = vm.uiState.value
            assertEquals("1", state.primary?.call?.id)
            assertEquals("2", state.held?.call?.id)
            assertEquals(ConferenceAction.Resume("2"), state.swap)
            assertEquals(ConferenceAction.Merge("1", "2"), state.merge)

            vm.onEvent(InCallEvent.Swap)
            vm.onEvent(InCallEvent.Merge)
            assertEquals(
                listOf("perform ${ConferenceAction.Resume("2")}", "perform ${ConferenceAction.Merge("1", "2")}"),
                controller.commands,
            )
        }

    @Test
    fun withoutHoldOrCallsNothingIsSent() =
        runTest {
            observe()
            calls.value = InCallState(listOf(call("1", CallStatus.DIALING)))
            vm.onEvent(InCallEvent.ToggleHold)
            vm.onEvent(InCallEvent.Swap)
            vm.onEvent(InCallEvent.Merge)
            assertTrue(controller.commands.isEmpty())

            calls.value = InCallState(listOf(call("1", CallStatus.DISCONNECTED)))
            assertTrue(vm.uiState.value.isOver)
            vm.onEvent(InCallEvent.KeyDown('1'))
            assertTrue(controller.commands.isEmpty())
        }

    @Test
    fun hiddenAndNonNumberCallers() =
        runTest {
            observe()
            calls.value = InCallState(listOf(call("1", CallStatus.RINGING, handle = null), call("2", CallStatus.DIALING, handle = "911")))
            assertNull(
                vm.uiState.value.ringing
                    ?.number,
            )
            assertEquals(
                "911",
                vm.uiState.value.primary
                    ?.number,
            )

            vm.onEvent(InCallEvent.SelectSim("2", "0"))
            vm.onEvent(InCallEvent.Refresh)
            assertEquals(listOf("sim 2 0", "refresh"), controller.commands)
        }

    @Test
    fun theUsersOwnQuickRepliesReplaceTheSystemOnes() =
        runTest {
            observe()
            val ringing = call("1", CallStatus.RINGING).copy(cannedReplies = listOf("Can't talk now"))
            calls.value = InCallState(listOf(ringing))
            assertEquals(listOf("Can't talk now"), vm.uiState.value.repliesFor(ringing))

            userReplies.value = listOf("Driving, call you later", "In a meeting")
            assertEquals(listOf("Driving, call you later", "In a meeting"), vm.uiState.value.repliesFor(ringing))
        }
}
