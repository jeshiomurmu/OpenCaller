package io.github.jeshiomurmu.opencaller.feature.voip

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A ringing app call rings over the lock screen; a call in progress is a quiet ongoing notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class VoipNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun aRingingCallIsAFullScreenCallNotificationThatRings() {
        val ringing = AppCallUi(AppCallPhase.RINGING_IN, incoming = true, number = "+12015550123", name = "Asha")
        val notification = VoipNotifications(context).build(ringing)
        assertEquals(VoipNotifications.CHANNEL_INCOMING, notification.channelId)
        assertEquals(Notification.CATEGORY_CALL, notification.category)
        assertNotNull(notification.fullScreenIntent)
        assertTrue(notification.flags and Notification.FLAG_INSISTENT != 0)
        assertEquals("Asha", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        val channel = manager.getNotificationChannel(VoipNotifications.CHANNEL_INCOMING)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNotNull(channel.sound)
    }

    @Test
    fun aCallInProgressIsQuiet() {
        val active = AppCallUi(AppCallPhase.ACTIVE, incoming = false, number = "+12015550123")
        val notification = VoipNotifications(context).build(active)
        assertEquals(VoipNotifications.CHANNEL_ONGOING, notification.channelId)
        assertNull(notification.fullScreenIntent)
        assertEquals(0, notification.flags and Notification.FLAG_INSISTENT)
        assertEquals("+12015550123", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, manager.getNotificationChannel(VoipNotifications.CHANNEL_ONGOING).importance)
    }
}
