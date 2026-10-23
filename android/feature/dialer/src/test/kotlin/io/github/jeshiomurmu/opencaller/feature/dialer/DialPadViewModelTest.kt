package io.github.jeshiomurmu.opencaller.feature.dialer

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.AccessStatus
import io.github.jeshiomurmu.opencaller.core.model.PermissionState
import io.github.jeshiomurmu.opencaller.core.model.PermissionStatus
import io.github.jeshiomurmu.opencaller.core.model.RoleState
import io.github.jeshiomurmu.opencaller.core.model.RoleStatus
import io.github.jeshiomurmu.opencaller.core.telephony.AccessStatusSource
import io.github.jeshiomurmu.opencaller.core.telephony.CallPlacer
import io.github.jeshiomurmu.opencaller.core.telephony.DeviceCallPlacer
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
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

@OptIn(ExperimentalCoroutinesApi::class)
class DialPadViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val placer =
        object : CallPlacer {
            var permitted = true
            var result: AppResult<Unit> = AppResult.Success(Unit)
            val placed = mutableListOf<String>()
            val handedOff = mutableListOf<String>()

            override fun canPlaceCalls() = permitted

            override fun place(number: String) = result.also { placed += number }

            override fun handOff(number: String) = result.also { handedOff += number }
        }

    private fun status(dialer: RoleStatus) =
        AccessStatus(
            RoleState(callScreening = RoleStatus.HELD, dialer = dialer, sms = RoleStatus.APP_NOT_ELIGIBLE),
            PermissionState(PermissionStatus.GRANTED, PermissionStatus.GRANTED, PermissionStatus.GRANTED),
        )

    private val access =
        object : AccessStatusSource {
            val mutable = MutableStateFlow(status(RoleStatus.HELD))
            override val status: StateFlow<AccessStatus> = mutable

            override fun refresh() = Unit
        }

    private val vm by lazy { DialPadViewModel(placer, access) }

    private fun TestScope.observe() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    private fun type(keys: String) = keys.forEach { vm.onEvent(DialPadEvent.Key(it)) }

    @Test
    fun keysBuildTheNumberAndCallPlacesItThroughTelecom() =
        runTest {
            observe()
            type("2015550123x")
            vm.onEvent(DialPadEvent.Delete)
            assertEquals("201555012", vm.uiState.value.number)
            type("3")
            vm.onEvent(DialPadEvent.Call)
            assertEquals(listOf("2015550123"), placer.placed)
            assertTrue(vm.uiState.value.isDefaultPhoneApp)
            assertNull(vm.uiState.value.problem)

            vm.onEvent(DialPadEvent.Clear)
            vm.onEvent(DialPadEvent.Call)
            assertEquals("an empty number is never called", 1, placer.placed.size)
        }

    @Test
    fun aTelLinkFillsTheDialPadOnce() =
        runTest {
            observe()
            vm.onEvent(DialPadEvent.Prefill("+1 (201) 555-0123"))
            assertEquals("+12015550123", vm.uiState.value.number)
            vm.onEvent(DialPadEvent.Delete)
            vm.onEvent(DialPadEvent.Prefill("+1 (201) 555-0123"))
            assertEquals("a rotation doesn't refill it", "+1201555012", vm.uiState.value.number)
        }

    @Test
    fun theCallPermissionIsExplainedFirst() =
        runTest {
            observe()
            placer.permitted = false
            type("911")
            vm.onEvent(DialPadEvent.Call)
            assertTrue(vm.uiState.value.explainingPermission)
            assertTrue(placer.placed.isEmpty())

            vm.onEvent(DialPadEvent.PermissionResult(granted = false))
            assertFalse(vm.uiState.value.explainingPermission)
            assertEquals(DialProblem.NO_PERMISSION, vm.uiState.value.problem)

            placer.permitted = true
            vm.onEvent(DialPadEvent.Call)
            assertEquals(listOf("911"), placer.placed)
        }

    @Test
    fun notTheDefaultPhoneAppHandsTheNumberOver() =
        runTest {
            access.mutable.value = status(RoleStatus.NOT_HELD)
            observe()
            type("2015550123")
            vm.onEvent(DialPadEvent.Call)
            assertFalse(vm.uiState.value.isDefaultPhoneApp)
            assertEquals(listOf("2015550123"), placer.handedOff)
            assertTrue(placer.placed.isEmpty())
        }

    @Test
    fun failuresAreShownAndCanBeDismissed() =
        runTest {
            observe()
            placer.result = AppResult.Failure(AppError.Validation(DeviceCallPlacer.NO_TELEPHONY))
            type("1")
            vm.onEvent(DialPadEvent.Call)
            assertEquals(DialProblem.CANT_CALL, vm.uiState.value.problem)
            vm.onEvent(DialPadEvent.DismissProblem)
            assertNull(vm.uiState.value.problem)
        }
}
