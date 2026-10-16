package io.github.jeshiomurmu.opencaller.feature.callerid

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.datastore.inMemory
import io.github.jeshiomurmu.opencaller.core.model.AccessStatus
import io.github.jeshiomurmu.opencaller.core.model.CallerIdSurface
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.CallerVerification
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PermissionState
import io.github.jeshiomurmu.opencaller.core.model.PermissionStatus
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.RoleState
import io.github.jeshiomurmu.opencaller.core.model.RoleStatus
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.model.SpamVerdict
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.AccessStatusSource
import io.github.jeshiomurmu.opencaller.core.telephony.screening.CallerIdListener
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningResult
import io.github.jeshiomurmu.opencaller.core.testing.TestDispatcherProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class CallerIdTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val us = PhoneNumber("+12015550123")
    private val formatter = CallerCardFormatter(context, PhoneNumberNormalizer()) { "US" }
    private val notifications = CallerIdNotifications(context, formatter)

    @Before
    fun allowNotificationsAndRegisterTheLauncher() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Report opens the app through its launch intent, which needs a launcher activity.
        val launcher = ComponentName(context, "io.github.jeshiomurmu.opencaller.MainActivity")
        shadowOf(context.packageManager).addActivityIfNotPresent(launcher)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            launcher,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    @Test
    fun titlesAndSummariesAreWordsNotColours() {
        val spam = formatter.card(CallerInfo(us, spamVerdict = SpamVerdict(0.9f, RiskLevel.LIKELY_SPAM, "Loan", 214)))
        assertEquals("(201) 555-0123", formatter.title(spam))
        assertEquals("Likely scam · Loan · 214 reports", formatter.summary(spam))
        assertEquals("+1 ••• 0123", spam.maskedNumber)

        val contact = formatter.card(CallerInfo(us, "Amina", NameSource.CONTACT, verification = CallerVerification.PASSED))
        assertEquals("Amina", formatter.title(contact))
        assertEquals("Contact · Verified by carrier", formatter.summary(contact))

        val spoofed = formatter.card(CallerInfo(us, verification = CallerVerification.FAILED))
        assertEquals("No reports. Not a registered business. · Possibly spoofed", formatter.summary(spoofed))

        val hidden = formatter.card(CallerInfo(number = null))
        assertEquals("Hidden number", formatter.title(hidden))
        // A name without a source is never shown (docs/design.md §1).
        assertEquals("(201) 555-0123", formatter.title(formatter.card(CallerInfo(us, displayName = "Someone"))))
    }

    @Test
    fun headsUpCallerIdUsesAHighImportanceChannel() {
        assertTrue(notifications.showCallerId(formatter.card(CallerInfo(us))))
        val posted = shadowOf(manager).getNotification(CallerIdNotifications.CALLER_ID_NOTIFICATION)
        assertEquals(CallerIdNotifications.CHANNEL_CALLER_ID, posted.channelId)
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(CallerIdNotifications.CHANNEL_CALLER_ID).importance,
        )
        notifications.cancelCallerId()
        assertNull(shadowOf(manager).getNotification(CallerIdNotifications.CALLER_ID_NOTIFICATION))
    }

    @Test
    fun afterCallOffersAtMostThreeActions() {
        assertTrue(notifications.showAfterCall(formatter.card(CallerInfo(us))))
        assertEquals(listOf("Block", "Report", "Save contact"), actions(shadowOf(manager).allNotifications.single()))

        manager.cancelAll()
        val flagged = CallerInfo(us, spamVerdict = SpamVerdict(0.7f, RiskLevel.SUSPICIOUS, null, 3))
        assertTrue(notifications.showAfterCall(formatter.card(flagged)))
        assertEquals(listOf("Block", "Not spam", "Save contact"), actions(shadowOf(manager).allNotifications.single()))
        assertFalse(notifications.showAfterCall(formatter.card(CallerInfo(number = null))))
    }

    @Test
    fun nothingIsPostedWithoutTheNotificationPermission() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(notifications.showCallerId(formatter.card(CallerInfo(us))))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun withoutOverlayCallerIdIsAHeadsUpAndTheSummaryFollowsTheCall() {
        val watcher = FakeCallEndWatcher()
        val shown = mutableListOf<CallerIdSurface>()
        val controller = controller(watcher, overlay = PermissionStatus.DENIED) { _, surface -> shown += surface }

        controller.present(result(CallerInfo(us)))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(CallerIdSurface.NOTIFICATION), shown)
        assertEquals(1, shadowOf(manager).allNotifications.size)

        watcher.end()
        val left = shadowOf(manager).allNotifications.single()
        assertEquals(CallerIdNotifications.CHANNEL_AFTER_CALL, left.channelId)
    }

    @Test
    fun contactsGetNoSummaryAndTheDefaultPhoneAppShowsNothing() {
        val watcher = FakeCallEndWatcher()
        controller(
            watcher,
            overlay = PermissionStatus.DENIED,
        ).present(result(CallerInfo(us, "Amina", NameSource.CONTACT), ScreeningReason.CONTACT))
        shadowOf(Looper.getMainLooper()).idle()
        watcher.end()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())

        val shown = mutableListOf<CallerIdSurface>()
        controller(FakeCallEndWatcher(), overlay = PermissionStatus.GRANTED, dialer = RoleStatus.HELD) { _, s -> shown += s }
            .present(result(CallerInfo(us)))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(shown.isEmpty())
    }

    private fun controller(
        watcher: CallEndWatcher,
        overlay: PermissionStatus,
        dialer: RoleStatus = RoleStatus.APP_NOT_ELIGIBLE,
        listener: CallerIdListener = CallerIdListener { _, _ -> },
    ): CallerIdController {
        val status =
            AccessStatus(
                RoleState(RoleStatus.HELD, dialer, RoleStatus.APP_NOT_ELIGIBLE),
                PermissionState(PermissionStatus.GRANTED, PermissionStatus.GRANTED, overlay, PermissionStatus.GRANTED),
            )
        val access =
            object : AccessStatusSource {
                override val status = MutableStateFlow(status)

                override fun refresh() {
                    // The status is fixed for each test.
                }
            }
        return CallerIdController(
            access = access,
            settings = UserSettingsDataSource.inMemory(),
            formatter = formatter,
            surfaces = CallerIdSurfaces(CallerCardOverlay(context), notifications, watcher),
            listeners = setOf(listener),
            dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher()),
        )
    }

    private fun result(
        caller: CallerInfo,
        reason: ScreeningReason = ScreeningReason.NO_MATCH,
    ) = ScreeningResult(Decision.ALLOW, reason, Direction.INCOMING, caller)

    private fun actions(notification: Notification): List<String> = notification.actions.map { it.title.toString() }

    private class FakeCallEndWatcher : CallEndWatcher {
        private var onEnded: (() -> Unit)? = null

        override fun canObserveEveryCall(): Boolean = true

        override fun watch(onEnded: () -> Unit): CallWatch {
            this.onEnded = onEnded
            return CallWatch { this.onEnded = null }
        }

        fun end() {
            onEnded?.invoke()
        }
    }
}
