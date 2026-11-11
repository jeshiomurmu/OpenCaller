package io.github.jeshiomurmu.opencaller.feature.assistant

import androidx.lifecycle.SavedStateHandle
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantRoomClient
import io.github.jeshiomurmu.opencaller.core.model.AssistantCallRoom
import io.github.jeshiomurmu.opencaller.core.model.CallUrgency
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEnd
import io.github.jeshiomurmu.opencaller.core.model.Speaker
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Optional

@OptIn(ExperimentalCoroutinesApi::class)
class LiveScreeningViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val assistant = FakeAssistant()
    private val room = FakeRoom()

    private fun vm(client: Optional<AssistantRoomClient> = Optional.of(room)) =
        LiveScreeningViewModel(SavedStateHandle(mapOf(LiveScreeningViewModel.EXTRA_CALL_ID to CALL_ID)), assistant, client)

    // TC-AST-001 on the phone: the transcript and the details arrive live, Call back opens the dialer, and the call
    // is kept in the history on this phone.
    @Test
    fun theLiveScreenFollowsTheCallAndCallsBack() =
        runTest {
            val vm = vm()
            assertEquals(LivePhase.LIVE, vm.uiState.value.phase)
            room.agent("""{"t":"hello","v":1,"caller":"+8801711000099","callerHidden":false}""")
            room.agent("""{"t":"line","role":"assistant","text":"Hello, you've reached an automated call assistant."}""")
            room.agent("""{"t":"partial","text":"It's Rah"}""")
            room.agent("""{"t":"line","role":"caller","text":"It's Rahim from the clinic."}""")
            room.agent("""{"t":"details","name":"Rahim","reason":"Appointment","urgency":"high","likelySpam":false}""")
            val state = vm.uiState.value
            assertEquals(PhoneNumber("+8801711000099"), state.caller)
            assertEquals(listOf(Speaker.ASSISTANT, Speaker.CALLER), state.lines.map { it.speaker })
            assertNull(state.partial)
            assertEquals(CallUrgency.HIGH, state.details?.urgency)

            vm.onEvent(LiveEvent.Reply("  Five minutes, please "))
            vm.onEvent(LiveEvent.CallBack)
            assertEquals(listOf("""{"t":"reply","text":"Five minutes, please"}""", """{"t":"callback"}"""), room.sent)
            assertEquals(PhoneNumber("+8801711000099"), vm.uiState.value.dial)
            vm.onEvent(LiveEvent.Dialled)
            assertNull(vm.uiState.value.dial)

            room.agent("""{"t":"ended","reason":"callback"}""")
            assertEquals(LivePhase.ENDED, vm.uiState.value.phase)
            val kept = assistant.kept.last()
            assertEquals(CALL_ID, kept.id)
            assertEquals(ScreeningEnd.CALL_BACK, kept.end)
            assertEquals("Rahim", kept.details.name)
            assertEquals(2, kept.transcript.size)
            assertTrue(room.closed)
        }

    @Test
    fun spamEndsTheCallAndUnknownMessagesAreIgnored() =
        runTest {
            val vm = vm()
            room.agent("""{"t":"hello","v":1,"caller":null,"callerHidden":true}""")
            room.agent("""{"t":"something-new"}""")
            room.agent("not json")
            assertTrue(vm.uiState.value.callerHidden)
            vm.onEvent(LiveEvent.Spam)
            assertEquals(listOf("""{"t":"spam"}"""), room.sent)
            vm.onEvent(LiveEvent.CallBack) // no number to call back
            assertNull(vm.uiState.value.dial)
            room.hangUp()
            assertEquals(LivePhase.ENDED, vm.uiState.value.phase)
        }

    @Test
    fun withoutLiveKitOrARoomTheScreenSaysSo() =
        runTest {
            assertEquals(LivePhase.NOT_IN_BUILD, vm(Optional.empty()).uiState.value.phase)
            assistant.roomResult = AppResult.Success(AssistantCallRoom(CALL_ID, 5, ended = true, room = "ast_1", url = null, token = null))
            assertEquals(LivePhase.ENDED, vm().uiState.value.phase)
            val failing = FakeRoom(joins = false)
            assistant.roomResult =
                AppResult.Success(AssistantCallRoom(CALL_ID, 5, ended = false, room = "ast_1", url = "wss://x", token = "t"))
            assertEquals(LivePhase.FAILED, vm(Optional.of(failing)).uiState.value.phase)
            vm(Optional.of(failing)).onEvent(LiveEvent.Spam)
            assertEquals(emptyList<String>(), failing.sent)
        }
}
