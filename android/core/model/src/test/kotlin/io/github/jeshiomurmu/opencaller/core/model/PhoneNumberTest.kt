package io.github.jeshiomurmu.opencaller.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumberTest {
    @Test
    fun acceptsE164() {
        assertEquals("+8801712345678", PhoneNumber("+8801712345678").e164)
        assertTrue(PhoneNumber.isE164("+12025550123"))
    }

    @Test
    fun rejectsNonE164() {
        listOf("01712345678", "+0123456789", "+12", "+1234567890123456", "+1 202 555 0123", "").forEach {
            assertFalse(it, PhoneNumber.isE164(it))
            assertThrows { PhoneNumber(it) }
        }
    }

    @Test
    fun toStringDoesNotLeakTheNumber() {
        val text = PhoneNumber("+8801712345678").toString()
        assertFalse(text.contains("88017123"))
        assertEquals("PhoneNumber(+****5678)", text)
    }

    @Test
    fun senderIdValidation() {
        assertEquals("AD-HDFCBK", SenderId("AD-HDFCBK").value)
        listOf("", " ", " Google", "A".repeat(SenderId.MAX_LENGTH + 1), "Bad\u0007").forEach {
            assertThrows { SenderId(it) }
        }
    }

    @Test
    fun otpInfoHidesCode() {
        assertFalse(OtpInfo(code = "482913").toString().contains("482913"))
        assertThrows { OtpInfo(code = " ") }
    }

    @Test
    fun spamVerdictValidation() {
        assertEquals(RiskLevel.LIKELY_SPAM, SpamVerdict(0.9f, RiskLevel.LIKELY_SPAM, "Loan", 214).level)
        assertThrows { SpamVerdict(1.5f, RiskLevel.LIKELY_SPAM, null, 0) }
        assertThrows { SpamVerdict(0.5f, RiskLevel.SUSPICIOUS, null, -1) }
    }

    @Test
    fun callerInfoFlags() {
        assertTrue(CallerInfo(number = null).isHidden)
        val business = CallerInfo(number = PhoneNumber("+12025550123"), verifiedBusinessId = "b1")
        assertFalse(business.isHidden)
        assertTrue(business.isVerifiedBusiness)
    }

    private fun assertThrows(block: () -> Unit) {
        val thrown =
            try {
                block()
                false
            } catch (_: IllegalArgumentException) {
                true
            }
        assertTrue("expected IllegalArgumentException", thrown)
    }
}
