package io.github.jeshiomurmu.opencaller.feature.social

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.model.FlashKind
import io.github.jeshiomurmu.opencaller.core.model.FlashMessage
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** A received Flash message shows as a notification with the sender and the text in the reader's language. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class FlashNotifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun aFlashMessageIsANotification() {
        FlashNotifier(
            context,
        ).show(FlashMessage("f1", FlashKind.RUNNING_LATE, PhoneNumber("+12015550123"), "Asha", 1_000L, outgoing = false))
        FlashNotifier(context).show(FlashMessage("f2", FlashKind.CALL_ME, PhoneNumber("+12015550124"), null, 2_000L, outgoing = false))
        val posted = shadowOf(manager).allNotifications
        assertEquals(2, posted.size)
        val first = posted.first { it.extras.getString(Notification.EXTRA_TEXT) == "Running late" }
        assertEquals("Flash from Asha", first.extras.getString(Notification.EXTRA_TITLE))
        val second = posted.first { it.extras.getString(Notification.EXTRA_TEXT) == "Call me" }
        // Without a visible name, the number.
        assertEquals("Flash from +12015550124", second.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(FlashNotifier.CHANNEL).importance)
    }

    @Test
    fun everyKindHasText() {
        FlashKind.entries.forEach { kind -> assert(context.getString(kind.label()).isNotBlank()) }
    }
}
