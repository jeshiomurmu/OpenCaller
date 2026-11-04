package io.github.jeshiomurmu.opencaller.feature.callerid

import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.feature.callerid.messenger.MessengerApp
import io.github.jeshiomurmu.opencaller.feature.callerid.messenger.MessengerCalls
import io.github.jeshiomurmu.opencaller.feature.callerid.messenger.PostedNotification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TC-UT-004: a messenger's call notification gives a number only when one is written in it. */
class MessengerCallsTest {
    private val normalizer = PhoneNumberNormalizer()

    private fun posted(
        packageName: String = "com.whatsapp",
        category: String? = MessengerCalls.CATEGORY_CALL,
        template: String? = null,
        texts: List<String> = emptyList(),
    ) = PostedNotification(packageName, category, template, texts)

    @Test
    fun recognisesTheThreeMessengers() {
        assertEquals(MessengerApp.WHATSAPP, MessengerApp.of("com.whatsapp"))
        assertEquals(MessengerApp.WHATSAPP, MessengerApp.of("com.whatsapp.w4b"))
        assertEquals(MessengerApp.TELEGRAM, MessengerApp.of("org.telegram.messenger"))
        assertEquals(MessengerApp.SIGNAL, MessengerApp.of("org.thoughtcrime.securesms"))
        assertNull(MessengerApp.of("com.example.chat"))
    }

    @Test
    fun onlyCallNotificationsOfAMessengerCount() {
        assertTrue(MessengerCalls.isMessengerCall(posted()))
        assertTrue(MessengerCalls.isMessengerCall(posted("org.thoughtcrime.securesms", null, MessengerCalls.CALL_STYLE_TEMPLATE)))
        // A message from a messenger, and a call from another app, are ignored.
        assertFalse(MessengerCalls.isMessengerCall(posted(category = "msg")))
        assertFalse(MessengerCalls.isMessengerCall(posted("com.example.voip")))
    }

    @Test
    fun theNumberComesFromTheTextOnly() {
        val unsaved = posted(texts = listOf("+880 1712-345678", "Incoming voice call"))
        assertEquals(PhoneNumber("+8801712345678"), MessengerCalls.numberIn(unsaved, "BD", normalizer))
        val national = posted("org.telegram.messenger", texts = listOf("Telegram call", "01712 345678"))
        assertEquals(PhoneNumber("+8801712345678"), MessengerCalls.numberIn(national, "BD", normalizer))
        // Saved under a name, or no number shown: nothing is guessed.
        assertNull(MessengerCalls.numberIn(posted(texts = listOf("Mum", "Incoming voice call")), "BD", normalizer))
        assertNull(MessengerCalls.numberIn(posted(texts = listOf("Ringing…")), "BD", normalizer))
    }
}
