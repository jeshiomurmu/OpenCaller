package io.github.jeshiomurmu.opencaller.feature.profile

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.account.Account
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
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

/** Account that answers with whatever the test set. */
class FakeAccount(
    override val serverConfigured: Boolean = true,
) : Account {
    val signedInState = MutableStateFlow(false)
    override val signedIn: Flow<Boolean> = signedInState
    override val unlistPageUrl: String? = if (serverConfigured) "http://10.0.2.2:8080/unlist" else null
    var sendResult: AppResult<Long> = AppResult.Success(300)
    var verifyResult: AppResult<Unit> = AppResult.Success(Unit)
    val codesSent = mutableListOf<PhoneNumber>()
    val verified = mutableListOf<Pair<PhoneNumber, String>>()

    override suspend fun sendCode(number: PhoneNumber): AppResult<Long> = sendResult.also { codesSent += number }

    override suspend fun verify(
        number: PhoneNumber,
        code: String,
    ): AppResult<Unit> =
        verifyResult.also {
            verified += number to code
            if (it is AppResult.Success) signedInState.value = true
        }

    override suspend fun signOut() {
        signedInState.value = false
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SignInViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val account = FakeAccount()
    private val vm by lazy { SignInViewModel(account, PhoneNumberNormalizer()) { "US" } }

    private fun TestScope.observe(flow: StateFlow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    @Test
    fun numberThenCodeSignsIn() =
        runTest {
            observe(vm.uiState)
            vm.onEvent(SignInEvent.SendCode("(202) 555-0143"))
            val code = vm.uiState.value
            assertEquals(SignInStep.CODE, code.step)
            assertEquals(PhoneNumber("+12025550143"), code.number)
            assertEquals("(202) 555-0143", code.display)
            assertEquals(300L, code.codeValidSeconds)
            assertEquals(listOf(PhoneNumber("+12025550143")), account.codesSent)

            vm.onEvent(SignInEvent.Verify("123 456"))
            assertEquals(SignInStep.DONE, vm.uiState.value.step)
            assertEquals(listOf(PhoneNumber("+12025550143") to "123456"), account.verified)
            assertTrue(account.signedInState.value)
        }

    @Test
    fun badNumbersAndShortCodesNeverReachTheServer() =
        runTest {
            observe(vm.uiState)
            vm.onEvent(SignInEvent.SendCode("12"))
            assertEquals(SignInProblem.InvalidNumber, vm.uiState.value.problem)
            assertTrue(account.codesSent.isEmpty())

            vm.onEvent(SignInEvent.SendCode("+44 20 7946 0958"))
            assertEquals(PhoneNumber("+442079460958"), vm.uiState.value.number)
            vm.onEvent(SignInEvent.Verify("12345"))
            assertEquals(SignInProblem.CodeFormat, vm.uiState.value.problem)
            assertTrue(account.verified.isEmpty())
        }

    @Test
    fun serverAnswersBecomeProblems() =
        runTest {
            observe(vm.uiState)
            account.sendResult = AppResult.Failure(AppError.RateLimited(3_599))
            vm.onEvent(SignInEvent.SendCode("+12025550143"))
            assertEquals(SignInProblem.TooMany(3_599), vm.uiState.value.problem)
            assertEquals(SignInStep.NUMBER, vm.uiState.value.step)
            assertFalse(vm.uiState.value.busy)

            account.sendResult = AppResult.Success(300)
            vm.onEvent(SignInEvent.SendCode("+12025550143"))
            account.verifyResult = AppResult.Failure(AppError.Validation("invalid_code"))
            vm.onEvent(SignInEvent.Verify("000000"))
            assertEquals(SignInProblem.WrongCode, vm.uiState.value.problem)
            account.verifyResult = AppResult.Failure(AppError.Validation("code_invalidated"))
            vm.onEvent(SignInEvent.Verify("000000"))
            assertEquals(SignInProblem.CodeExpired, vm.uiState.value.problem)

            vm.onEvent(SignInEvent.ResendCode)
            assertNull(vm.uiState.value.problem)
            assertEquals(3, account.codesSent.size)

            vm.onEvent(SignInEvent.ChangeNumber)
            assertEquals(SignInUiState(), vm.uiState.value)
        }

    @Test
    fun errorMapping() {
        assertEquals(SignInProblem.Offline, AppError.Network.toProblem(sending = true))
        assertEquals(SignInProblem.NotConnected, AppError.ServerNotConfigured.toProblem(sending = true))
        assertEquals(SignInProblem.InvalidNumber, AppError.Validation("invalid_number").toProblem(sending = true))
        assertEquals(SignInProblem.ServerError, AppError.Validation("invalid_request").toProblem(sending = false))
        assertEquals(SignInProblem.CodeExpired, AppError.Validation("code_expired").toProblem(sending = false))
        assertEquals(SignInProblem.ServerError, AppError.Unexpected(IllegalStateException("boom")).toProblem(sending = true))
    }

    @Test
    fun withoutAServerTheScreenSaysSo() {
        val offline = SignInViewModel(FakeAccount(serverConfigured = false), PhoneNumberNormalizer()) { "US" }
        assertFalse(offline.uiState.value.serverConfigured)
    }

    @Test
    fun accountSectionFollowsSignIn() =
        runTest {
            val section = AccountViewModel(account)
            observe(section.uiState)
            assertEquals(AccountUiState(false, true, false, "http://10.0.2.2:8080/unlist"), section.uiState.value)
            account.signedInState.value = true
            assertTrue(section.uiState.value.signedIn)
            section.signOut()
            assertFalse(section.uiState.value.signedIn)
        }
}
