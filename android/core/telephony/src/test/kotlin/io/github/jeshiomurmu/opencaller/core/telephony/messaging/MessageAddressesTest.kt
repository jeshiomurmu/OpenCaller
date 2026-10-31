package io.github.jeshiomurmu.opencaller.core.telephony.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageAddressesTest {
    @Test
    fun theSameNumberWrittenDifferentlyMatches() {
        assertTrue(MessageAddresses.same("+1 (555) 000-1111", "5550001111"))
        assertTrue(MessageAddresses.same("ALERTS", "alerts"))
        assertFalse(MessageAddresses.same("+15550001111", "+15550002222"))
        assertFalse("short codes compare whole", MessageAddresses.same("1234", "51234"))
        assertFalse(MessageAddresses.same("ana@example.com", "+15550001111"))
    }

    @Test
    fun aGroupMessageLeavesOutTheUser() {
        val group =
            MessageAddresses.othersInReceived(
                "+15550001111",
                listOf("+15559999999", "+15550002222"),
                listOf("+15550003333"),
                self = setOf("5559999999"),
            )
        assertEquals(listOf("+15550001111", "+15550002222", "+15550003333"), group)
    }

    @Test
    fun aMessageToTheUserAloneIsWithItsSender() {
        assertEquals(
            listOf("+15550001111"),
            MessageAddresses.othersInReceived("+15550001111", listOf("+15559999999"), emptyList(), self = emptySet()),
        )
    }

    @Test
    fun withoutOwnNumbersEveryRecipientOfAGroupStays() {
        val group =
            MessageAddresses.othersInReceived(
                "+15550001111",
                listOf("+15559999999", "+15550002222"),
                emptyList(),
                self = emptySet(),
            )
        assertEquals(listOf("+15550001111", "+15559999999", "+15550002222"), group)
    }

    @Test
    fun placeholdersAndRepeatsAreDropped() {
        val group =
            MessageAddresses.othersInReceived(
                MessageAddresses.INSERT_ADDRESS_TOKEN,
                listOf("+15550002222", "5550002222", ""),
                emptyList(),
                self = emptySet(),
            )
        assertEquals(listOf("+15550002222"), group)
        assertEquals(
            listOf("+15550002222", "ALERTS"),
            MessageAddresses.distinct(listOf(" +15550002222", "(555) 000-2222", "ALERTS", "alerts")),
        )
    }
}
