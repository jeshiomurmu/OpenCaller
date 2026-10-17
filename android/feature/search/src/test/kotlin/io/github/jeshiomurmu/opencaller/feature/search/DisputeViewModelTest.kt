package io.github.jeshiomurmu.opencaller.feature.search

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DisputeViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val search = FakeNumberSearch()
    private val vm by lazy { DisputeViewModel(search, FakeAccountStatus(), PhoneNumberNormalizer()) { "US" } }

    @Test
    fun aDisputeIsSentWithTheTrimmedReason() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            vm.submit("(202) 555-0143", "  This is my bakery's number.  ")
            assertEquals(7L, vm.uiState.value.sentId)
            assertNull(vm.uiState.value.problem)
            assertEquals(listOf(PhoneNumber("+12025550143") to "This is my bakery's number."), search.disputes)
        }

    @Test
    fun invalidInputNeverReachesTheServer() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            vm.submit("12", "reason")
            assertEquals(DisputeProblem.InvalidNumber, vm.uiState.value.problem)
            vm.submit("+12025550143", "   ")
            assertEquals(DisputeProblem.InvalidReason, vm.uiState.value.problem)
            vm.submit("+12025550143", "x".repeat(DisputeViewModel.MAX_REASON + 1))
            assertEquals(DisputeProblem.InvalidReason, vm.uiState.value.problem)
            assertTrue(search.disputes.isEmpty())
        }

    @Test
    fun serverErrors() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            mapOf(
                AppError.Network to DisputeProblem.Offline,
                AppError.RateLimited(60) to DisputeProblem.TooMany,
                AppError.Auth to DisputeProblem.SignInNeeded,
                AppError.Validation("invalid_request") to DisputeProblem.InvalidReason,
                AppError.Unexpected(IllegalStateException("500")) to DisputeProblem.ServerError,
            ).forEach { (error, problem) ->
                search.disputeResult = AppResult.Failure(error)
                vm.submit("+12025550143", "Not spam")
                assertEquals(problem, vm.uiState.value.problem)
                assertNull(vm.uiState.value.sentId)
            }
        }
}
