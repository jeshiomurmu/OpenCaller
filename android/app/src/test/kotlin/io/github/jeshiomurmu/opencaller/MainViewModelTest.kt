package io.github.jeshiomurmu.opencaller

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.datastore.inMemory
import io.github.jeshiomurmu.opencaller.core.model.AccessItemStatus
import io.github.jeshiomurmu.opencaller.core.model.Conversation
import io.github.jeshiomurmu.opencaller.core.model.Message
import io.github.jeshiomurmu.opencaller.core.model.OnboardingStep
import io.github.jeshiomurmu.opencaller.core.model.Participant
import io.github.jeshiomurmu.opencaller.core.model.RoleStatus
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.ConversationSource
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
    private val conversations = FakeConversations()

    @Test
    fun showsOnboardingUntilItIsDone() =
        runTest {
            val settings = UserSettingsDataSource.inMemory()
            val vm = MainViewModel(settings, access, conversations)
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
            val vm =
                MainViewModel(UserSettingsDataSource.inMemory(UserPreferences(onboardingStep = OnboardingStep.DONE)), access, conversations)
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
            val vm = MainViewModel(UserSettingsDataSource.inMemory(), access, conversations)
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

    @Test
    fun messagesTabOffersTheDefaultSmsAppUntilHeld() =
        runTest {
            val vm = MainViewModel(UserSettingsDataSource.inMemory(), access, conversations)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            access.status.value =
                access.status.value.copy(
                    roles =
                        access.status.value.roles
                            .copy(sms = RoleStatus.NOT_HELD),
                )
            assertEquals(AccessItemStatus.NOT_GRANTED, vm.uiState.value.defaultSmsApp)
            assertFalse(vm.uiState.value.isDefaultSmsApp)

            access.status.value =
                access.status.value.copy(
                    roles =
                        access.status.value.roles
                            .copy(sms = RoleStatus.HELD),
                )
            assertNull(vm.uiState.value.defaultSmsApp)
            assertTrue(vm.uiState.value.isDefaultSmsApp)
        }

    @Test
    fun aSendToLinkOpensTheConversationWithItsText() =
        runTest {
            val vm = MainViewModel(UserSettingsDataSource.inMemory(), access, conversations)
            vm.requestMessage(listOf("+15550001111", "+15550002222"), "Hi")
            assertEquals(MessageRequest(threadId = 42, draft = "Hi"), vm.pendingMessage.value)
            assertEquals(setOf("+15550001111", "+15550002222"), conversations.asked)

            vm.messageShown()
            vm.requestMessage(emptyList(), null)
            assertEquals("no recipients: a new message", MessageRequest(threadId = null), vm.pendingMessage.value)

            vm.openThread(7)
            assertEquals(MessageRequest(threadId = 7), vm.pendingMessage.value)
        }
}

/** Only [threadFor] is used by the app's view model. */
private class FakeConversations : ConversationSource {
    var asked: Set<String>? = null

    override fun canRead(): Boolean = true

    override fun observeConversations(): Flow<AppResult<List<Conversation>>> = emptyFlow()

    override fun observeThread(threadId: Long): Flow<AppResult<List<Message>>> = emptyFlow()

    override suspend fun thread(threadId: Long): AppResult<List<Message>> = AppResult.Success(emptyList())

    override suspend fun participants(threadId: Long): AppResult<List<Participant>> = AppResult.Success(emptyList())

    override suspend fun threadFor(addresses: Set<String>): AppResult<Long> {
        asked = addresses
        return AppResult.Success(42)
    }

    override suspend fun markRead(threadId: Long): AppResult<Int> = AppResult.Success(0)

    override suspend fun delete(threadId: Long): AppResult<Int> = AppResult.Success(0)
}
