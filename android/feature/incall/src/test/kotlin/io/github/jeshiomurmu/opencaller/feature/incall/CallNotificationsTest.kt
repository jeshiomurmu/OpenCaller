package io.github.jeshiomurmu.opencaller.feature.incall

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.SpamVerdict
import io.github.jeshiomurmu.opencaller.core.model.VerdictSource
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.InCallState
import io.github.jeshiomurmu.opencaller.core.telephony.incall.TrackedCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** TC-INCALL-001 (notification side): the incoming call carries a full-screen intent; the ongoing call has Hang up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q, Build.VERSION_CODES.VANILLA_ICE_CREAM])
class CallNotificationsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val notifications = CallNotifications(app, CallerText(app, CallNumbers(PhoneNumberNormalizer()) { "US" }))
    private val number = PhoneNumber("+12015550123")

    @Before
    fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun call(status: CallStatus) = TrackedCall("1", number.e164, hidden = false, status = status, direction = Direction.INCOMING)

    private fun postedOrNull(id: Int): Notification? = shadowOf(manager).getNotification(id)

    private fun posted(id: Int): Notification = checkNotNull(postedOrNull(id)) { "notification $id not posted" }

    @Test
    fun ringingCallIsAFullScreenCallNotification() {
        val scam = CallerInfo(number, spamVerdict = SpamVerdict(0.9f, RiskLevel.LIKELY_SPAM, null, 3, VerdictSource.SPAM_LIST))
        notifications.render(InCallState(listOf(call(CallStatus.RINGING))), mapOf("1" to scam))

        val incoming = posted(CallNotifications.INCOMING_ID)
        assertNotNull(incoming.fullScreenIntent)
        assertEquals(Notification.CATEGORY_CALL, incoming.category)
        assertEquals("(201) 555-0123", incoming.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Incoming call · Likely scam", incoming.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertEquals(Notification.CallStyle::class.java.name, incoming.extras.getString(Notification.EXTRA_TEMPLATE))
        } else {
            assertEquals(listOf("Decline", "Answer"), incoming.actions.map { it.title.toString() })
        }
        assertNull(postedOrNull(CallNotifications.ONGOING_ID))

        val channel = manager.getNotificationChannel(CallNotifications.CHANNEL_INCOMING)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull("Telecom rings, the channel stays silent", channel.sound)
    }

    @Test
    fun answeredCallReplacesTheIncomingNotification() {
        notifications.render(InCallState(listOf(call(CallStatus.RINGING))), emptyMap())
        val contact = CallerInfo(number, displayName = "Alex", nameSource = NameSource.CONTACT)
        notifications.render(InCallState(listOf(call(CallStatus.ACTIVE).copy(connectedAtMillis = 1_000))), mapOf("1" to contact))

        assertNull(postedOrNull(CallNotifications.INCOMING_ID))
        val ongoing = posted(CallNotifications.ONGOING_ID)
        assertEquals("Alex", ongoing.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Ongoing call", ongoing.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(listOf("Hang up"), ongoing.actions.map { it.title.toString() })
        assertTrue(ongoing.flags and Notification.FLAG_ONGOING_EVENT != 0)

        notifications.render(InCallState(), emptyMap())
        assertNull(postedOrNull(CallNotifications.ONGOING_ID))
    }

    @Test
    fun hiddenCallerAndCancelAll() {
        notifications.render(InCallState(listOf(call(CallStatus.RINGING).copy(handle = null, hidden = true))), emptyMap())
        assertEquals("Hidden number", posted(CallNotifications.INCOMING_ID).extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        notifications.cancelAll()
        assertFalse(shadowOf(manager).allNotifications.any())
    }
}
