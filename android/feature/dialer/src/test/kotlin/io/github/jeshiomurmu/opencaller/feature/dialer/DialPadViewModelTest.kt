package io.github.jeshiomurmu.opencaller.feature.dialer

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.AccessStatus
import io.github.jeshiomurmu.opencaller.core.model.CallLogEntry
import io.github.jeshiomurmu.opencaller.core.model.CallType
import io.github.jeshiomurmu.opencaller.core.model.ContactPhone
import io.github.jeshiomurmu.opencaller.core.model.ContactSummary
import io.github.jeshiomurmu.opencaller.core.model.PermissionState
import io.github.jeshiomurmu.opencaller.core.model.PermissionStatus
import io.github.jeshiomurmu.opencaller.core.model.PhoneLabel
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RoleState
import io.github.jeshiomurmu.opencaller.core.model.RoleStatus
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.AccessStatusSource
import io.github.jeshiomurmu.opencaller.core.telephony.DeviceCallPlacer
import io.github.jeshiomurmu.opencaller.core.telephony.NumberDisplay
import io.github.jeshiomurmu.opencaller.core.telephony.SimChoice
import io.github.jeshiomurmu.opencaller.core.telephony.SimOption
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import io.github.jeshiomurmu.opencaller.core.testing.TestDispatcherProvider
import io.github.jeshiomurmu.opencaller.feature.dialer.search.DialSuggestions
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

    private val placer = FakeCallPlacer()
    private val contacts = FakeContacts()
    private val callLog = FakeCallLog()
    private var sims = SimChoice()

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

    private lateinit var vm: DialPadViewModel

    private fun TestScope.start() {
        val dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler))
        vm =
            DialPadViewModel(
                placer = placer,
                access = access,
                sims = { sims },
                numbers = NumberDisplay(PhoneNumberNormalizer()) { "US" },
                suggestions = DialSuggestions(contacts, callLog),
                dispatchers = dispatchers,
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    private fun contact(
        id: Long,
        name: String,
        e164: String,
    ) = ContactSummary(id, "k$id", name, false, listOf(ContactPhone(PhoneNumber(e164), e164, PhoneLabel.MOBILE)))

    private fun type(keys: String) = keys.forEach { vm.onEvent(DialPadEvent.Key(it)) }

    @Test
    fun keysBuildTheNumberFormattedAsTypedAndCallPlacesIt() =
        runTest {
            start()
            type("2015550123x")
            vm.onEvent(DialPadEvent.Delete)
            assertEquals("201555012", vm.uiState.value.number)
            type("3")
            assertEquals("(201) 555-0123", vm.uiState.value.display)
            vm.onEvent(DialPadEvent.Call)
            assertEquals(listOf("2015550123" to null), placer.placed)
            assertTrue(vm.uiState.value.isDefaultPhoneApp)
            assertNull(vm.uiState.value.problem)
            assertEquals("a placed call clears the dial pad", "", vm.uiState.value.number)

            vm.onEvent(DialPadEvent.Call)
            assertEquals("an empty number is never called", 1, placer.placed.size)
        }

    @Test
    fun aTelLinkFillsTheDialPadOnce() =
        runTest {
            start()
            vm.onEvent(DialPadEvent.Prefill("+1 (201) 555-0123"))
            assertEquals("+12015550123", vm.uiState.value.number)
            vm.onEvent(DialPadEvent.Delete)
            vm.onEvent(DialPadEvent.Prefill("+1 (201) 555-0123"))
            assertEquals("a rotation doesn't refill it", "+1201555012", vm.uiState.value.number)
        }

    @Test
    fun pastedTextKeepsWhatCanBeDialled() =
        runTest {
            start()
            vm.onEvent(DialPadEvent.Paste("+1 (800) FLOWERS, 2"))
            assertEquals("+18003569377,2", vm.uiState.value.number)
        }

    @Test
    fun tcDial002SuggestionsComeFromContactsAndRecentCalls() =
        runTest {
            contacts.list.value =
                AppResult.Success(listOf(contact(1, "Ram Kumar", "+12015550111"), contact(2, "Sam Okafor", "+12015550122")))
            callLog.calls.value =
                AppResult.Success(
                    listOf(
                        CallLogEntry(9, PhoneNumber("+17265550100"), "+17265550100", null, CallType.MISSED, 1, 0),
                        CallLogEntry(8, PhoneNumber("+12015550111"), "+12015550111", null, CallType.OUTGOING, 0, 30),
                    ),
                )
            start()
            type("726")
            val suggestions = vm.uiState.value.suggestions
            assertEquals(listOf("Ram Kumar", "Sam Okafor", null), suggestions.map { it.name })
            assertEquals("(726) 555-0100", suggestions.last().display)

            vm.onEvent(DialPadEvent.CallSuggestion("+12015550122"))
            assertEquals(listOf("+12015550122" to null), placer.placed)

            vm.onEvent(DialPadEvent.Clear)
            type("*12")
            assertTrue(
                "codes get no suggestions",
                vm.uiState.value.suggestions
                    .isEmpty(),
            )
        }

    @Test
    fun tcDial003TwoSimsWithoutADefaultAskWhichOne() =
        runTest {
            sims = SimChoice(listOf(SimOption("a", "Airtel"), SimOption("b", "Work")), defaultId = null)
            start()
            type("2015550123")
            vm.onEvent(DialPadEvent.Call)
            assertEquals(sims.options, vm.uiState.value.simOptions)
            assertTrue(placer.placed.isEmpty())

            vm.onEvent(DialPadEvent.ChooseSim("b"))
            assertNull(vm.uiState.value.simOptions)
            assertEquals(listOf("2015550123" to "b"), placer.placed)

            // With a default SIM, Telecom uses it: no picker.
            sims = sims.copy(defaultId = "a")
            type("2015550124")
            vm.onEvent(DialPadEvent.Call)
            assertNull(vm.uiState.value.simOptions)
            assertEquals("2015550124" to null, placer.placed.last())
        }

    @Test
    fun closingTheSimPickerCallsNobody() =
        runTest {
            sims = SimChoice(listOf(SimOption("a", "Airtel"), SimOption("b", "Work")))
            start()
            type("2015550123")
            vm.onEvent(DialPadEvent.Call)
            vm.onEvent(DialPadEvent.DismissSims)
            assertNull(vm.uiState.value.simOptions)
            vm.onEvent(DialPadEvent.ChooseSim("a"))
            assertTrue(placer.placed.isEmpty())
            assertEquals("2015550123", vm.uiState.value.number)
        }

    @Test
    fun holdingOneCallsVoicemailAndHoldingZeroTypesPlus() =
        runTest {
            start()
            vm.onEvent(DialPadEvent.Key('1'))
            vm.onEvent(DialPadEvent.LongPress('1'))
            assertEquals(listOf<String?>(null), placer.voicemail)
            assertEquals("", vm.uiState.value.number)

            vm.onEvent(DialPadEvent.Key('0'))
            vm.onEvent(DialPadEvent.LongPress('0'))
            assertEquals("+", vm.uiState.value.number)

            // Only a lone 1 is voicemail.
            type("21")
            vm.onEvent(DialPadEvent.LongPress('1'))
            assertEquals(1, placer.voicemail.size)
        }

    @Test
    fun tcDial004CarrierCodesAreDialledAndSecretCodesGoToAndroid() =
        runTest {
            start()
            type("*123#")
            vm.onEvent(DialPadEvent.Call)
            assertEquals(listOf("*123#" to null), placer.placed)

            type("*#*#4636#*#*")
            vm.onEvent(DialPadEvent.Call)
            assertEquals(listOf("4636"), placer.secretCodes)
            assertEquals(1, placer.placed.size)

            placer.secretResult = AppResult.Failure(AppError.PermissionDenied)
            vm.onEvent(DialPadEvent.Call)
            assertEquals(DialProblem.CODE_REFUSED, vm.uiState.value.problem)
        }

    @Test
    fun tcBlk013EmergencyNumbersAreCalledAtOnce() =
        runTest {
            // No call permission and two SIMs without a default: neither the explainer nor the picker comes first.
            placer.permitted = false
            sims = SimChoice(listOf(SimOption("a", "Airtel"), SimOption("b", "Work")))
            start()
            type("112")
            vm.onEvent(DialPadEvent.Call)
            assertEquals(listOf("112"), placer.emergency)
            assertFalse(vm.uiState.value.explainingPermission)
            assertNull(vm.uiState.value.simOptions)
            assertTrue(placer.placed.isEmpty())
        }

    @Test
    fun theCallPermissionIsExplainedFirst() =
        runTest {
            placer.permitted = false
            start()
            type("2015550123")
            vm.onEvent(DialPadEvent.Call)
            assertTrue(vm.uiState.value.explainingPermission)
            assertTrue(placer.placed.isEmpty())

            vm.onEvent(DialPadEvent.PermissionResult(granted = false))
            assertFalse(vm.uiState.value.explainingPermission)
            assertEquals(DialProblem.NO_PERMISSION, vm.uiState.value.problem)

            vm.onEvent(DialPadEvent.Call)
            placer.permitted = true
            vm.onEvent(DialPadEvent.PermissionResult(granted = true))
            assertEquals("the waiting call goes ahead once allowed", listOf("2015550123" to null), placer.placed)
        }

    @Test
    fun notTheDefaultPhoneAppHandsTheNumberOver() =
        runTest {
            access.mutable.value = status(RoleStatus.NOT_HELD)
            start()
            type("2015550123")
            vm.onEvent(DialPadEvent.Call)
            assertFalse(vm.uiState.value.isDefaultPhoneApp)
            assertEquals(listOf("2015550123"), placer.handedOff)
            assertTrue(placer.placed.isEmpty())
        }

    @Test
    fun failuresAreShownAndCanBeDismissed() =
        runTest {
            start()
            placer.result = AppResult.Failure(AppError.Validation(DeviceCallPlacer.NO_TELEPHONY))
            type("1")
            vm.onEvent(DialPadEvent.Call)
            assertEquals(DialProblem.CANT_CALL, vm.uiState.value.problem)
            vm.onEvent(DialPadEvent.DismissProblem)
            assertNull(vm.uiState.value.problem)
        }
}
