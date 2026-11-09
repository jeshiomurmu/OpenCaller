package io.github.jeshiomurmu.opencaller.feature.family

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.model.FamilyAlert
import io.github.jeshiomurmu.opencaller.core.model.FamilyAlertKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** TC-FAM-002 on the admin's phone: the alert is a notification naming the member, with no number or code. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class FamilyAlertNotifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun anAlertIsANotificationNamingTheMember() {
        FamilyAlertNotifier(context).show(FamilyAlert("a1", "m1", "Mum", FamilyAlertKind.OTP_DURING_CALL, 1_000L))
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("Family alert: Mum", posted.extras.getString(Notification.EXTRA_TITLE))
        val text = posted.extras.getString(Notification.EXTRA_TEXT).orEmpty()
        assertTrue(text, "during a call with a stranger" in text)
        assertFalse(text.any { it.isDigit() })
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(FamilyAlertNotifier.CHANNEL).importance)
    }

    @Test
    fun everyKindHasText() {
        FamilyAlertKind.entries.forEach { kind -> assertTrue(context.getString(kind.text()).isNotBlank()) }
    }
}
