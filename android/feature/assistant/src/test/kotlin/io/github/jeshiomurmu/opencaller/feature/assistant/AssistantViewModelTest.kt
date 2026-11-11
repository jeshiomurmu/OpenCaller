package io.github.jeshiomurmu.opencaller.feature.assistant

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantOffer
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val assistant = FakeAssistant()
    private val offer = MutableStateFlow(AssistantOffer.AVAILABLE)
    private val vm by lazy { AssistantViewModel(assistant) { offer } }

    @Test
    fun theUserGetsANumberWithItsCodesAndSeesTheMinutes() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            vm.onEvent(AssistantEvent.Refresh)
            assertNull(vm.uiState.value.line)
            assertEquals(
                2,
                vm.uiState.value.usage
                    ?.usedMinutes,
            ) // 61 s count as 2 minutes

            vm.onEvent(AssistantEvent.TurnOn)
            assertEquals(
                NUMBER,
                vm.uiState.value.line
                    ?.number,
            )
            assertEquals(
                "**61*+8809638000001#",
                vm.uiState.value.codes
                    .first()
                    .code,
            )

            vm.onEvent(AssistantEvent.TurnOff)
            assertNull(vm.uiState.value.line)
            assertEquals(emptyList<ForwardingCode>(), vm.uiState.value.codes)
        }

    @Test
    fun refusalsAndTheOfferAreShown() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            assistant.allocateResult = AppResult.Failure(AppError.Validation("supporter_required"))
            vm.onEvent(AssistantEvent.TurnOn)
            assertEquals(R.string.assistant_supporter, vm.uiState.value.message)
            vm.onEvent(AssistantEvent.MessageShown)
            assistant.allocateResult = AppResult.Failure(AppError.Validation("assistant_unavailable"))
            vm.onEvent(AssistantEvent.TurnOn)
            assertEquals(R.string.assistant_unavailable, vm.uiState.value.message)
            offer.value = AssistantOffer.NOT_IN_BUILD
            assertEquals(AssistantOffer.NOT_IN_BUILD, vm.uiState.value.offer)
        }
}
