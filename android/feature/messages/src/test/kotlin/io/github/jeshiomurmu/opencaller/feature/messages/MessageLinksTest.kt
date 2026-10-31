package io.github.jeshiomurmu.opencaller.feature.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MessageLinksTest {
    @Test
    fun linksGiveTheirRecipientsAndText() {
        assertEquals(listOf("+15550001111"), MessageLinks.recipients("+15550001111"))
        assertEquals(listOf("+15550001111", "+15550002222"), MessageLinks.recipients("+15550001111,+15550002222?body=Hi"))
        assertEquals("semicolons too", listOf("5550001111", "5550002222"), MessageLinks.recipients("5550001111;5550002222"))
        assertEquals(emptyList<String>(), MessageLinks.recipients(""))
        assertEquals(emptyList<String>(), MessageLinks.recipients(null))
        assertEquals("Hi there +1", MessageLinks.body("+15550001111?body=Hi%20there%20+1"))
        assertNull(MessageLinks.body("+15550001111"))
        assertEquals(setOf("sms", "smsto", "mms", "mmsto"), MessageLinks.SCHEMES)
    }
}
