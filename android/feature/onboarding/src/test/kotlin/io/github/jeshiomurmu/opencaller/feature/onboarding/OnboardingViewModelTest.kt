package io.github.jeshiomurmu.opencaller.feature.onboarding

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.datastore.inMemory
import io.github.jeshiomurmu.opencaller.core.model.AccessItem
import io.github.jeshiomurmu.opencaller.core.model.AccessRequestOutcome
import io.github.jeshiomurmu.opencaller.core.model.AppRole
import io.github.jeshiomurmu.opencaller.core.model.OnboardingStep
import io.github.jeshiomurmu.opencaller.core.model.PermissionStatus
import io.github.jeshiomurmu.opencaller.core.model.RoleStatus
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import io.github.jeshiomurmu.opencaller.core.telephony.AccessRequest
import io.github.jeshiomurmu.opencaller.core.telephony.AccessRequestFactory
import io.github.jeshiomurmu.opencaller.core.telephony.RoleManagerHelper
import io.github.jeshiomurmu.opencaller.core.telephony.androidName
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowRoleManager

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class OnboardingViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val access = FakeAccessStatusSource()
    private val settings = UserSettingsDataSource.inMemory()
    private lateinit var requests: AccessRequestFactory

    @Before
    fun setUp() {
        // Robolectric treats a role as available while it has any holder; "" stands for "some other app".
        AppRole.entries.forEach { ShadowRoleManager.addRoleHolder(it.androidName, "", Process.myUserHandle()) }
        requests = AccessRequestFactory(context, RoleManagerHelper(context))
    }

    @Test
    fun walksWelcomeThenPrivacyThenTheCallScreeningExplainer() =
        runTest {
            val vm = viewModel()
            assertEquals(OnboardingPage.Welcome, vm.page())
            assertEquals(1, vm.uiState.value.stepNumber)

            vm.onEvent(OnboardingEvent.Next)
            assertEquals(OnboardingPage.Privacy, vm.page())

            vm.onEvent(OnboardingEvent.Next)
            assertEquals(OnboardingPage.Explainer(AccessItem.CALL_SCREENING, opensSettings = false), vm.page())
            assertEquals(3, vm.uiState.value.stepNumber)
            assertEquals(OnboardingStep.CALL_SCREENING, settings.preferences.first().onboardingStep)
        }

    @Test
    fun everyRequestComesFromAnExplainerContinue() =
        runTest {
            // TC-ONB-004: walk the whole flow; a request may only appear right after Continue on an explainer.
            val vm = viewModel()
            val seen = mutableListOf<OnboardingUiState>()
            subscribe(vm) { seen += it }
            vm.onEvent(OnboardingEvent.Next)
            vm.onEvent(OnboardingEvent.Next)
            repeat(times = 3) {
                assertNull(vm.uiState.value.pendingRequest)
                vm.onEvent(OnboardingEvent.ExplainerContinue)
                assertNotNull(vm.uiState.value.pendingRequest)
                vm.onEvent(OnboardingEvent.RequestLaunched)
                vm.onEvent(OnboardingEvent.RequestResult(AccessRequestOutcome.DENIED))
            }
            assertTrue(vm.page() is OnboardingPage.Extras)

            val withRequest = seen.withIndex().filter { it.value.pendingRequest != null }
            assertEquals(3, withRequest.size)
            withRequest.forEach { (index, state) ->
                val explainer = state.page as OnboardingPage.Explainer
                assertEquals(explainer, seen[index - 1].page)
                assertNull(seen[index - 1].pendingRequest)
            }
        }

    @Test
    fun asksForTheRoleThenContactsThenNotifications() =
        runTest {
            val vm = viewModel(UserPreferences(onboardingStep = OnboardingStep.CALL_SCREENING))
            vm.onEvent(OnboardingEvent.ExplainerContinue)
            assertTrue(vm.uiState.value.pendingRequest is AccessRequest.SystemScreen)

            vm.onEvent(OnboardingEvent.RequestLaunched)
            access.status.value =
                access.status.value.copy(
                    roles =
                        access.status.value.roles
                            .copy(callScreening = RoleStatus.HELD),
                )
            vm.onEvent(OnboardingEvent.RequestResult(AccessRequestOutcome.GRANTED))
            assertEquals(OnboardingPage.Explainer(AccessItem.CONTACTS, opensSettings = false), vm.page())
            assertEquals(1, access.refreshes)

            vm.onEvent(OnboardingEvent.ExplainerContinue)
            assertEquals(AccessRequest.RuntimePermission(Manifest.permission.READ_CONTACTS), vm.uiState.value.pendingRequest)
            vm.onEvent(OnboardingEvent.RequestLaunched)
            vm.onEvent(OnboardingEvent.RequestResult(AccessRequestOutcome.DENIED))

            assertEquals(OnboardingPage.Explainer(AccessItem.NOTIFICATIONS, opensSettings = false), vm.page())
            vm.onEvent(OnboardingEvent.ExplainerContinue)
            assertEquals(AccessRequest.RuntimePermission(Manifest.permission.POST_NOTIFICATIONS), vm.uiState.value.pendingRequest)
        }

    @Test
    fun notNowOnTheRoleStillMovesOn() =
        runTest {
            // TC-ONB-002: denying call screening never blocks onboarding.
            val vm = viewModel(UserPreferences(onboardingStep = OnboardingStep.CALL_SCREENING))
            vm.onEvent(OnboardingEvent.ExplainerNotNow)
            assertEquals(OnboardingPage.Explainer(AccessItem.CONTACTS, opensSettings = false), vm.page())
            assertNull(vm.uiState.value.pendingRequest)
        }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun skipsNotificationsBeforeAndroid13() =
        runTest {
            access.status.value =
                access.status.value.copy(
                    permissions =
                        access.status.value.permissions
                            .copy(notifications = PermissionStatus.NOT_REQUIRED),
                )
            val vm = viewModel(UserPreferences(onboardingStep = OnboardingStep.CONTACTS))
            vm.onEvent(OnboardingEvent.ExplainerNotNow)
            assertTrue(vm.page() is OnboardingPage.Extras)
        }

    @Test
    fun extrasOfferOnlyWhatTheAppCanTakeAndReturnAfterARequest() =
        runTest {
            // TC-ONB-003: the dialer role is optional; it shows as "coming later" until the app is eligible.
            val vm = viewModel(UserPreferences(onboardingStep = OnboardingStep.EXTRAS))
            val extras = vm.page() as OnboardingPage.Extras
            assertEquals(
                listOf(AccessItem.DEFAULT_PHONE_APP, AccessItem.DEFAULT_SMS_APP, AccessItem.OVERLAY),
                extras.options.map { it.item },
            )

            vm.onEvent(OnboardingEvent.OpenExtra(AccessItem.DEFAULT_PHONE_APP))
            assertTrue("coming-later items don't open", vm.page() is OnboardingPage.Extras)

            vm.onEvent(OnboardingEvent.OpenExtra(AccessItem.OVERLAY))
            assertEquals(OnboardingPage.Explainer(AccessItem.OVERLAY, opensSettings = true), vm.page())
            vm.onEvent(OnboardingEvent.ExplainerContinue)
            vm.onEvent(OnboardingEvent.RequestLaunched)
            // TC-ONB-007: overlay denied; back on the extras list, onboarding continues.
            vm.onEvent(OnboardingEvent.RequestResult(AccessRequestOutcome.DENIED))
            assertTrue(vm.page() is OnboardingPage.Extras)

            vm.onEvent(OnboardingEvent.Finish)
            assertEquals(OnboardingPage.Finished, vm.page())
            assertEquals(OnboardingStep.DONE, settings.preferences.first().onboardingStep)
        }

    @Test
    fun resumesAtTheSameStepAfterProcessDeath() =
        runTest {
            // TC-ONB-008: the step lives in user settings, the open optional item in SavedStateHandle.
            val first = viewModel(UserPreferences(onboardingStep = OnboardingStep.EXTRAS))
            first.onEvent(OnboardingEvent.OpenExtra(AccessItem.OVERLAY))
            val saved = lastHandle.keys().associateWith { lastHandle.get<Any?>(it) }

            val restored = OnboardingViewModel(settings, access, requests, SavedStateHandle(saved))
            subscribe(restored)
            assertEquals(OnboardingPage.Explainer(AccessItem.OVERLAY, opensSettings = true), restored.page())
        }

    @Test
    fun aDoubleTapAdvancesOnlyOnce() =
        runTest {
            val vm = viewModel()
            vm.onEvent(OnboardingEvent.Next)
            vm.onEvent(OnboardingEvent.Next)
            vm.onEvent(OnboardingEvent.ExplainerNotNow)
            vm.onEvent(OnboardingEvent.ExplainerNotNow)
            assertEquals(OnboardingStep.NOTIFICATIONS, settings.preferences.first().onboardingStep)
        }

    private var lastHandle = SavedStateHandle()

    private suspend fun TestScope.viewModel(initial: UserPreferences? = null): OnboardingViewModel {
        initial?.let { prefs -> settings.update { prefs } }
        lastHandle = SavedStateHandle()
        return OnboardingViewModel(settings, access, requests, lastHandle).also { subscribe(it) }
    }

    // Collects eagerly so the WhileSubscribed state stays live and every emission is seen.
    private fun TestScope.subscribe(
        vm: OnboardingViewModel,
        onState: (OnboardingUiState) -> Unit = {},
    ) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect(onState) }
    }

    private fun OnboardingViewModel.page(): OnboardingPage = uiState.value.page
}
