package io.github.jeshiomurmu.opencaller.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRiskTest {
    @Test
    fun everyRiskHasItsOwnBit() {
        val bits = MessageRisk.entries.map { it.bit }
        assertEquals(bits.size, bits.toSet().size)
        assertTrue(bits.all { Integer.bitCount(it) == 1 })
    }

    @Test
    fun bitsRoundTrip() {
        val risks = setOf(MessageRisk.LOOKALIKE_LINK, MessageRisk.OTP_BURST)
        assertEquals(risks, MessageRisk.fromBits(MessageRisk.toBits(risks)))
        assertEquals(emptySet<MessageRisk>(), MessageRisk.fromBits(0))
        // Bits written by a newer version are ignored, not misread.
        assertEquals(setOf(MessageRisk.LOOKALIKE_LINK), MessageRisk.fromBits(1 or (1 shl 30)))
    }

    @Test
    fun storedBitValuesNeverChange() {
        assertEquals(1, MessageRisk.LOOKALIKE_LINK.bit)
        assertEquals(1 shl 5, MessageRisk.OTP_DURING_CALL.bit)
        assertEquals(1 shl 7, MessageRisk.UNREQUESTED_OTP.bit)
    }
}
