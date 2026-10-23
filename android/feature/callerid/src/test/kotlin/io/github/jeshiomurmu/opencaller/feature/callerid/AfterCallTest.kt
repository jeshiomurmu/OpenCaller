package io.github.jeshiomurmu.opencaller.feature.callerid

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.AccessStatus
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PermissionState
import io.github.jeshiomurmu.opencaller.core.model.PermissionStatus
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.RoleState
import io.github.jeshiomurmu.opencaller.core.model.RoleStatus
import io.github.jeshiomurmu.opencaller.core.model.SpamVerdict
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.AccessStatusSource
import io.github.jeshiomurmu.opencaller.core.telephony.incall.EndedCall
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The default phone app's after-call card: a screen while the in-call screen is in front, else the notification. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class AfterCallTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = context as Application
    private val us = PhoneNumber("+12015550123")
    private val formatter = CallerCardFormatter(context, PhoneNumberNormalizer()) { "US" }
    private val ended = EndedCalls()
    private val access =
        object : AccessStatusSource {
            override val status =
                MutableStateFlow(
                    AccessStatus(
                        RoleState(RoleStatus.HELD, RoleStatus.HELD, RoleStatus.APP_NOT_ELIGIBLE),
                        PermissionState(PermissionStatus.GRANTED, PermissionStatus.GRANTED, PermissionStatus.GRANTED),
                    ),
                )

            override fun refresh() {
                // Fixed for each test.
            }
        }
    private val presenter = AfterCallScreens(context, formatter, CallerIdNotifications(context, formatter), ended, access)

    private val actions =
        object : AfterCallActions {
            val done = mutableListOf<String>()
            var result: AppResult<Unit> = AppResult.Success(Unit)

            override suspend fun block(number: PhoneNumber) = result.also { done += "block ${number.e164}" }

            override suspend fun notSpam(number: PhoneNumber) = result.also { done += "not spam ${number.e164}" }
        }

    @Before
    fun allowNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun visibleInCallScreenLeadsToTheAfterCallScreen() {
        presenter.present(EndedCall(CallerInfo(us), Direction.INCOMING, answered = false), inCallScreenVisible = true)

        val started = shadowOf(app).nextStartedActivity
        assertEquals(AfterCallActivity::class.java.name, started.component?.className)
        assertEquals(us.e164, started.getStringExtra(AfterCallActivity.EXTRA_NUMBER))
        assertFalse(started.getBooleanExtra(AfterCallActivity.EXTRA_ANSWERED, true))
        assertEquals(CallerInfo(us), ended.take(us))
    }

    @Test
    fun otherwiseTheNotification() {
        presenter.present(EndedCall(CallerInfo(us), Direction.INCOMING, answered = true), inCallScreenVisible = false)

        assertNull(shadowOf(app).nextStartedActivity)
        val posted = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        assertEquals(1, posted.size)
        assertNull("nothing is kept for a screen that never opens", ended.take(us))
    }

    @Test
    fun onlyIncomingCallsFromNonContactsWantTheCard() {
        assertTrue(EndedCall(CallerInfo(us), Direction.INCOMING, answered = true).wantsAfterCall)
        assertFalse(EndedCall(CallerInfo(us), Direction.OUTGOING, answered = true).wantsAfterCall)
        assertFalse(EndedCall(CallerInfo(us, "Amina", NameSource.CONTACT), Direction.INCOMING, answered = true).wantsAfterCall)
        assertFalse(EndedCall(CallerInfo(null), Direction.INCOMING, answered = false).wantsAfterCall)
    }

    @Test
    fun theScreenBlocksOrMarksNotSpam() {
        val flagged = CallerInfo(us, spamVerdict = SpamVerdict(0.7f, RiskLevel.SUSPICIOUS, null, 3))
        ended.put(flagged)
        val vm = viewModel()
        assertTrue(vm.uiState.value.flagged)
        assertFalse(vm.uiState.value.answered)
        assertEquals(
            "(201) 555-0123",
            vm.uiState.value.card
                ?.formattedNumber,
        )

        vm.onEvent(AfterCallEvent.NotSpam)
        assertEquals(AfterCallDone.MARKED_NOT_SPAM, vm.uiState.value.done)

        actions.result = AppResult.Failure(AppError.Storage)
        vm.onEvent(AfterCallEvent.Block)
        assertEquals(AfterCallDone.FAILED, vm.uiState.value.done)
        assertEquals(listOf("not spam ${us.e164}", "block ${us.e164}"), actions.done)
    }

    @Test
    fun afterARestartOnlyTheNumberIsKnown() {
        val vm = viewModel()
        assertEquals(
            us,
            vm.uiState.value.card
                ?.caller
                ?.number,
        )
        assertFalse(vm.uiState.value.flagged)
    }

    private fun viewModel() =
        AfterCallViewModel(
            savedState = SavedStateHandle(mapOf(AfterCallActivity.EXTRA_NUMBER to us.e164, AfterCallActivity.EXTRA_ANSWERED to false)),
            ended = ended,
            formatter = formatter,
            actions = actions,
        )
}
