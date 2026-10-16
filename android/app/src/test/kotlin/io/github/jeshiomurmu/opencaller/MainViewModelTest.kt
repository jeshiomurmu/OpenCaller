package io.github.jeshiomurmu.opencaller

import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.datastore.inMemory
import io.github.jeshiomurmu.opencaller.core.model.AccessItemStatus
import io.github.jeshiomurmu.opencaller.core.model.OnboardingStep
import io.github.jeshiomurmu.opencaller.core.model.RoleStatus
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val access = FakeAccessStatusSource()

    @Test
    fun showsOnboardingUntilItIsDone() =
        runTest {
            val settings = UserSettingsDataSource.inMemory()
            val vm = MainViewModel(settings, access)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            assertFalse(vm.uiState.value.loading)
            assertTrue(vm.uiState.value.showOnboarding)

            settings.update { it.copy(onboardingStep = OnboardingStep.DONE) }
            assertFalse(vm.uiState.value.showOnboarding)
        }

    @Test
    fun degradedWhileCallScreeningIsOffAndBannerGoesAwayWhenGranted() =
        runTest {
            // TC-ONB-002 / TC-ONB-005: the banner follows the role, which is re-read on every resume.
            val vm = MainViewModel(UserSettingsDataSource.inMemory(UserPreferences(onboardingStep = OnboardingStep.DONE)), access)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            assertTrue(vm.uiState.value.degraded)

            access.status.value =
                access.status.value.copy(
                    roles =
                        access.status.value.roles
                            .copy(callScreening = RoleStatus.HELD),
                )
            assertFalse(vm.uiState.value.degraded)
        }

    @Test
    fun callsTabOffersTheDefaultPhoneAppUntilHeld() =
        runTest {
            // TC-ONB-003.
            val vm = MainViewModel(UserSettingsDataSource.inMemory(), access)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            assertEquals(AccessItemStatus.COMING_LATER, vm.uiState.value.defaultPhoneApp)

            access.status.value =
                access.status.value.copy(
                    roles =
                        access.status.value.roles
                            .copy(dialer = RoleStatus.NOT_HELD),
                )
            assertEquals(AccessItemStatus.NOT_GRANTED, vm.uiState.value.defaultPhoneApp)

            access.status.value =
                access.status.value.copy(
                    roles =
                        access.status.value.roles
                            .copy(dialer = RoleStatus.HELD),
                )
            assertNull(vm.uiState.value.defaultPhoneApp)
        }
}
