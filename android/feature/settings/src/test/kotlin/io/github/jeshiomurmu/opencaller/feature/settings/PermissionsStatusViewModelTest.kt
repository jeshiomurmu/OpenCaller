package io.github.jeshiomurmu.opencaller.feature.settings

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.datastore.inMemory
import io.github.jeshiomurmu.opencaller.core.model.AccessItem
import io.github.jeshiomurmu.opencaller.core.model.AccessItemStatus
import io.github.jeshiomurmu.opencaller.core.model.AccessRequestOutcome
import io.github.jeshiomurmu.opencaller.core.model.AfterCallSurface
import io.github.jeshiomurmu.opencaller.core.model.AppRole
import io.github.jeshiomurmu.opencaller.core.model.CallerIdSurface
import io.github.jeshiomurmu.opencaller.core.model.PermissionStatus
import io.github.jeshiomurmu.opencaller.core.telephony.AccessRequest
import io.github.jeshiomurmu.opencaller.core.telephony.AccessRequestFactory
import io.github.jeshiomurmu.opencaller.core.telephony.RoleManagerHelper
import io.github.jeshiomurmu.opencaller.core.telephony.androidName
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class PermissionsStatusViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val access = FakeAccessStatusSource()
    private lateinit var vm: PermissionsStatusViewModel

    @Before
    fun setUp() {
        // Robolectric treats a role as available while it has any holder; "" stands for "some other app".
        AppRole.entries.forEach { ShadowRoleManager.addRoleHolder(it.androidName, "", Process.myUserHandle()) }
        val requests = AccessRequestFactory(context, RoleManagerHelper(context))
        vm = PermissionsStatusViewModel(UserSettingsDataSource.inMemory(), access, requests, SavedStateHandle())
    }

    @Test
    fun listsEveryApplicableItemAndHowCallerIdFallsBack() =
        runTest {
            subscribe()
            val state = vm.uiState.value
            // Full-screen calls only matter to the default phone app.
            assertEquals(AccessItem.entries - AccessItem.FULL_SCREEN_CALLS, state.rows.map { it.item })
            assertEquals(AccessItemStatus.NOT_GRANTED, state.rows.first().status)
            assertEquals(AccessItemStatus.COMING_LATER, state.rows.single { it.item == AccessItem.DEFAULT_PHONE_APP }.status)
            // TC-ONB-006 and TC-ONB-007: without notifications and overlay, the fallbacks are shown.
            assertEquals(CallerIdSurface.NOTIFICATION, state.callerIdSurface)
            assertEquals(AfterCallSurface.IN_APP_HISTORY, state.afterCallSurface)

            access.status.value =
                access.status.value.copy(
                    permissions =
                        access.status.value.permissions
                            .copy(notifications = PermissionStatus.NOT_REQUIRED),
                )
            assertFalse(
                vm.uiState.value.rows
                    .any { it.item == AccessItem.NOTIFICATIONS },
            )
        }

    @Test
    fun fixShowsTheExplainerBeforeAnyRequest() =
        runTest {
            subscribe()
            vm.onEvent(PermissionsStatusEvent.Fix(AccessItem.CONTACTS))
            assertEquals(AccessItem.CONTACTS, vm.uiState.value.explaining)
            assertNull(vm.uiState.value.pendingRequest)
            assertFalse(vm.uiState.value.explainerOpensSettings)

            vm.onEvent(PermissionsStatusEvent.ExplainerContinue)
            assertEquals(AccessRequest.RuntimePermission(Manifest.permission.READ_CONTACTS), vm.uiState.value.pendingRequest)
            vm.onEvent(PermissionsStatusEvent.RequestLaunched)
            vm.onEvent(PermissionsStatusEvent.RequestResult(AccessRequestOutcome.DENIED))
            assertNull(vm.uiState.value.explaining)
            assertEquals(1, access.refreshes)
        }

    @Test
    fun blockedPermissionGoesToAppSettings() =
        runTest {
            subscribe()
            fixAndAnswer(AccessItem.CONTACTS, AccessRequestOutcome.BLOCKED)
            assertTrue(
                vm.uiState.value.rows
                    .single { it.item == AccessItem.CONTACTS }
                    .needsSettings,
            )

            vm.onEvent(PermissionsStatusEvent.Fix(AccessItem.CONTACTS))
            assertTrue(vm.uiState.value.explainerOpensSettings)
            vm.onEvent(PermissionsStatusEvent.ExplainerContinue)
            val request = vm.uiState.value.pendingRequest as AccessRequest.SystemScreen
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, request.intent.action)
        }

    @Test
    fun roleRefusedTwiceGoesToDefaultAppsSettings() =
        runTest {
            subscribe()
            fixAndAnswer(AccessItem.CALL_SCREENING, AccessRequestOutcome.DENIED)
            vm.onEvent(PermissionsStatusEvent.Fix(AccessItem.CALL_SCREENING))
            assertFalse("one refusal: ask again with the system dialog", vm.uiState.value.explainerOpensSettings)
            vm.onEvent(PermissionsStatusEvent.ExplainerNotNow)

            fixAndAnswer(AccessItem.CALL_SCREENING, AccessRequestOutcome.DENIED)
            vm.onEvent(PermissionsStatusEvent.Fix(AccessItem.CALL_SCREENING))
            assertTrue(vm.uiState.value.explainerOpensSettings)
            vm.onEvent(PermissionsStatusEvent.ExplainerContinue)
            val request = vm.uiState.value.pendingRequest as AccessRequest.SystemScreen
            assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, request.intent.action)
        }

    @Test
    fun bannerEntryOpensTheExplainerOnlyOnce() =
        runTest {
            subscribe()
            vm.openOnce(AccessItem.CALL_SCREENING)
            assertEquals(AccessItem.CALL_SCREENING, vm.uiState.value.explaining)
            vm.onEvent(PermissionsStatusEvent.ExplainerNotNow)
            vm.openOnce(AccessItem.CALL_SCREENING)
            assertNull(vm.uiState.value.explaining)
        }

    @Test
    fun missingSettingsPageIsReported() =
        runTest {
            subscribe()
            fixAndAnswer(AccessItem.OVERLAY, AccessRequestOutcome.UNAVAILABLE)
            assertTrue(vm.uiState.value.settingsPageMissing)
        }

    @Test
    fun itemsThatCantBeFixedDontOpen() =
        runTest {
            subscribe()
            vm.onEvent(PermissionsStatusEvent.Fix(AccessItem.DEFAULT_SMS_APP))
            assertNull(vm.uiState.value.explaining)
        }

    private fun fixAndAnswer(
        item: AccessItem,
        outcome: AccessRequestOutcome,
    ) {
        vm.onEvent(PermissionsStatusEvent.Fix(item))
        vm.onEvent(PermissionsStatusEvent.ExplainerContinue)
        vm.onEvent(PermissionsStatusEvent.RequestLaunched)
        vm.onEvent(PermissionsStatusEvent.RequestResult(outcome))
    }

    private fun TestScope.subscribe() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }
}
