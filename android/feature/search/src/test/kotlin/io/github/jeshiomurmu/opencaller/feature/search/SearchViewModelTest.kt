package io.github.jeshiomurmu.opencaller.feature.search

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.lookup.SearchResult
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.datastore.inMemory
import io.github.jeshiomurmu.opencaller.core.model.LineType
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.SpamThreshold
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import io.github.jeshiomurmu.opencaller.core.model.VerdictSource
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import io.github.jeshiomurmu.opencaller.core.ui.WhyReason
import io.github.jeshiomurmu.opencaller.core.ui.whyReasons
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class SearchViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val search = FakeNumberSearch()
    private val account = FakeAccountStatus()
    private val settings = UserSettingsDataSource.inMemory(UserPreferences(spamThreshold = SpamThreshold.MEDIUM))
    private val normalizer = PhoneNumberNormalizer()
    private val number = PhoneNumber("+12025550143")

    private fun viewModel(status: FakeAccountStatus = account) = SearchViewModel(search, status, settings, normalizer) { "US" }

    private fun TestScope.observe(flow: StateFlow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    @Test
    fun aSpamResultShowsItsSourceReportsAndRisk() =
        runTest {
            search.searchResult = {
                AppResult.Success(SearchResult(it, "Ana Loans", NameSource.SELF_REGISTERED, 0.7f, "spam", "loan", 12, 2, null))
            }
            val vm = viewModel()
            observe(vm.uiState)
            vm.search("(202) 555-0143")
            val state = vm.uiState.value
            assertEquals(listOf(number), search.searched)
            val found = requireNotNull(state.found)
            assertEquals("(202) 555-0143", found.formattedNumber)
            assertEquals(2, found.notSpamCount)
            assertEquals("Ana Loans", found.caller.displayName)
            assertEquals(NameSource.SELF_REGISTERED, found.caller.nameSource)
            val verdict = requireNotNull(found.caller.spamVerdict)
            assertEquals(RiskLevel.LIKELY_SPAM, verdict.level)
            assertEquals(12, verdict.reportCount)
            assertEquals(VerdictSource.SEARCH, verdict.source)
            assertEquals("US", found.caller.regionCode)
            assertEquals(LineType.FIXED_LINE_OR_MOBILE, found.caller.lineType)
            assertEquals(
                listOf(WhyReason.SelfRegistered, WhyReason.Searched, WhyReason.Reports(12)),
                whyReasons(found.caller),
            )
            assertEquals(49, state.quota?.remaining)
        }

    @Test
    fun nothingKnownIsAResultToo() =
        runTest {
            val vm = viewModel()
            observe(vm.uiState)
            vm.search("+12025550143")
            val found = requireNotNull(vm.uiState.value.found)
            assertNull(found.caller.spamVerdict)
            assertEquals(listOf(WhyReason.NoData), whyReasons(found.caller))
        }

    @Test
    fun verifiedBusinessesAreMarked() =
        runTest {
            search.searchResult =
                {
                    AppResult.Success(
                        SearchResult(it, "City Pharmacy", NameSource.VERIFIED_BUSINESS, null, null, null, 0, 0, "City Pharmacy"),
                    )
                }
            val vm = viewModel()
            observe(vm.uiState)
            vm.search("+12025550143")
            assertTrue(requireNotNull(vm.uiState.value.found).caller.isVerifiedBusiness)
        }

    @Test
    fun errorStates() =
        runTest {
            val vm = viewModel()
            observe(vm.uiState)
            vm.search("abc")
            assertEquals(SearchProblem.InvalidNumber, vm.uiState.value.problem)
            assertTrue(search.searched.isEmpty())

            val cases =
                mapOf(
                    AppError.Network to SearchProblem.Offline,
                    AppError.RateLimited(3_600) to SearchProblem.QuotaUsed,
                    AppError.Auth to SearchProblem.SignInNeeded,
                    AppError.Unexpected(IllegalStateException("500")) to SearchProblem.ServerError,
                )
            cases.forEach { (error, problem) ->
                search.searchResult = { AppResult.Failure(error) }
                vm.search("+12025550143")
                assertEquals(problem, vm.uiState.value.problem)
                assertNull(vm.uiState.value.found)
                assertFalse(vm.uiState.value.busy)
            }
        }

    @Test
    fun signInAndServerStatus() =
        runTest {
            val signedOut = FakeAccountStatus().apply { signedInState.value = false }
            val vm = viewModel(signedOut)
            observe(vm.uiState)
            assertFalse(vm.uiState.value.signedIn)
            assertTrue(vm.uiState.value.serverConfigured)
            val noServer = viewModel(FakeAccountStatus(serverConfigured = false))
            observe(noServer.uiState)
            assertFalse(noServer.uiState.value.serverConfigured)
        }
}
