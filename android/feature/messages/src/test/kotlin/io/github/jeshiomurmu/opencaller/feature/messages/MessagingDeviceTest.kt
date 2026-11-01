package io.github.jeshiomurmu.opencaller.feature.messages

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.model.Message
import io.github.jeshiomurmu.opencaller.core.model.MessageBox
import io.github.jeshiomurmu.opencaller.core.model.MessageKey
import io.github.jeshiomurmu.opencaller.core.model.MessageKind
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.NumberDisplay
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.StoredMessage
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ContactLookup
import io.github.jeshiomurmu.opencaller.feature.messages.notify.ActiveThread
import io.github.jeshiomurmu.opencaller.feature.messages.notify.MessageNotifier
import io.github.jeshiomurmu.opencaller.feature.messages.notify.ParticipantNames
import io.github.jeshiomurmu.opencaller.feature.messages.receive.IncomingSmsReader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** TC-SMS-001 on the device side: a real SMS-DELIVER PDU is read, and its conversation gets a notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class MessagingDeviceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val conversations = FakeConversations()
    private val active = ActiveThread()
    private val names =
        ParticipantNames(
            object : ContactLookup {
                override fun canRead() = true

                override suspend fun displayName(number: PhoneNumber): String? = "Ana Silva".takeIf { number.e164 == "+15550001111" }
            },
            NumberDisplay(PhoneNumberNormalizer()) { "US" },
        )
    private val notifier = MessageNotifier(app, conversations, names, active)

    @Before
    fun allowNotifications() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun anSmsDeliverBroadcastIsRead() {
        // SMS-DELIVER from +15550001111, GSM 7-bit "Hi", sent 2026-10-26 12:00:00 UTC (3GPP TS 23.040 §9.2.2.1).
        val pdu =
            byteArrayOf(
                0x00,
                0x04,
                0x0B,
                0x91.toByte(),
                0x51,
                0x55,
                0x00,
                0x10,
                0x11,
                0xF1.toByte(),
                0x00,
                0x00,
                0x62,
                0x01,
                0x62,
                0x21,
                0x00,
                0x00,
                0x00,
                0x02,
                0xC8.toByte(),
                0x34,
            )
        val intent =
            Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION)
                .putExtra("pdus", arrayOf<Any>(pdu))
                .putExtra("format", "3gpp")
                .putExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, 3)

        val sms = checkNotNull(IncomingSmsReader.read(intent))
        assertEquals("+15550001111", sms.address)
        assertEquals("Hi", sms.body)
        assertEquals(1_793_016_000_000L, sms.sentAtMillis)
        assertEquals(3, sms.subscriptionId)
    }

    @Test
    fun aConversationGetsOneMessagingNotification() =
        runTest {
            val ana = person("+15550001111")
            conversations.people[7] = listOf(ana)
            conversations.threads.value =
                mapOf(
                    7L to
                        listOf(
                            Message(MessageKey(MessageKind.SMS, 1), 7, MessageBox.INBOX, ana, "Read already", 1_000, read = true),
                            Message(MessageKey(MessageKind.SMS, 2), 7, MessageBox.INBOX, ana, "Dinner at eight?", 2_000, read = false),
                        ),
                )

            notifier.stored(StoredMessage(MessageKey(MessageKind.SMS, 2), 7), senderName = "Ana Silva")

            val posted = manager.activeNotifications.single { it.tag == MessageNotifier.TAG_THREAD && it.id == 7 }.notification
            assertEquals(Notification.CATEGORY_MESSAGE, posted.category)
            assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
            assertNotNull("the lock screen shows only \"New message\"", posted.publicVersion)
            assertEquals(Notification.MessagingStyle::class.java.name, posted.extras.getString(Notification.EXTRA_TEMPLATE))
            // Only the unread message; each one is a Bundle with its "text" (Notification.MessagingStyle.Message).
            @Suppress("DEPRECATION") // The typed getParcelableArray needs API 33; the bundles are read the same way.
            val messages =
                posted.extras.getParcelableArray(Notification.EXTRA_MESSAGES).orEmpty().map {
                    (it as android.os.Bundle).getCharSequence("text").toString()
                }
            assertEquals(listOf("Dinner at eight?"), messages)
            assertEquals(listOf("Reply", "Mark as read"), posted.actions.map { it.title.toString() })
            assertTrue("Reply takes typed text", posted.actions[0].remoteInputs.isNotEmpty())
            assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(MessageNotifier.CHANNEL_MESSAGES).importance)
        }

    @Test
    fun theConversationOnScreenGetsNoNotification() =
        runTest {
            val ana = person("+15550001111")
            conversations.threads.value =
                mapOf(7L to listOf(Message(MessageKey(MessageKind.SMS, 2), 7, MessageBox.INBOX, ana, "Hi", 2_000, read = false)))
            active.set(7)

            notifier.stored(StoredMessage(MessageKey(MessageKind.SMS, 2), 7), senderName = null)

            assertTrue(manager.activeNotifications.none { it.tag == MessageNotifier.TAG_THREAD })
            assertEquals("it counts as read", listOf(7L), conversations.markedRead)
        }
}
