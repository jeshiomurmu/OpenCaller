package io.github.jeshiomurmu.opencaller.feature.otp

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** TC-OTP-005 and TC-OTP-009 on the device side: the warning's notifications. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class OtpGuardAlarmTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val alarm = OtpGuardAlarm(app)

    @Before
    fun allowNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun aCodeDuringACallOpensTheWarningOverTheLockScreen() {
        alarm.warnDuringCall("Ridely", flashCall = false)

        val posted = manager.activeNotifications.single { it.id == OtpGuardAlarm.GUARD_ID }.notification
        assertEquals(OtpGuardAlarm.CHANNEL_GUARD, posted.channelId)
        assertNotNull("a full-screen intent opens the warning", posted.fullScreenIntent)
        assertEquals(Notification.CATEGORY_CALL, posted.category)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(OtpGuardAlarm.CHANNEL_GUARD).importance)
        val opened = shadowOf(app).nextStartedActivity
        assertEquals(OtpGuardActivity::class.java.name, opened.component?.className)
        assertEquals("Ridely", opened.getStringExtra(OtpGuardActivity.EXTRA_FROM))
    }

    @Test
    fun tcOtp009ABurstOfCodesRaisesTheAlert() {
        alarm.warnBurst(3)

        val posted = manager.activeNotifications.single { it.id == OtpGuardAlarm.BURST_ID }.notification
        assertEquals("Several sign-in codes at once", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(
            posted.extras
                .getCharSequence(Notification.EXTRA_TEXT)
                .toString()
                .startsWith("3 codes arrived"),
        )
    }
}
