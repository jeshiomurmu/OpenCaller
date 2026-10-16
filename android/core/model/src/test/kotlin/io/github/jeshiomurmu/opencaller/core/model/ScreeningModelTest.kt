package io.github.jeshiomurmu.opencaller.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreeningModelTest {
    @Test
    fun thresholdsMatchTheSpec() {
        assertEquals(0.85f, SpamThreshold.HIGH.minimumScore)
        assertEquals(0.6f, SpamThreshold.MEDIUM.minimumScore)
        assertEquals(0.4f, SpamThreshold.LOW.minimumScore)
    }

    @Test
    fun scoreMapsToRiskLevelPerThreshold() {
        // TC-BLK-009: 0.9 under HIGH is spam, 0.7 under HIGH is only suspicious.
        assertEquals(RiskLevel.LIKELY_SPAM, SpamVerdict.levelFor(0.9f, SpamThreshold.HIGH))
        assertEquals(RiskLevel.SUSPICIOUS, SpamVerdict.levelFor(0.7f, SpamThreshold.HIGH))
        assertEquals(RiskLevel.LIKELY_SPAM, SpamVerdict.levelFor(0.85f, SpamThreshold.HIGH))
        assertEquals(RiskLevel.LIKELY_SPAM, SpamVerdict.levelFor(0.7f, SpamThreshold.MEDIUM))
        assertEquals(RiskLevel.LIKELY_SPAM, SpamVerdict.levelFor(0.4f, SpamThreshold.LOW))
        assertEquals(RiskLevel.NONE, SpamVerdict.levelFor(0.39f, SpamThreshold.LOW))
        assertEquals(RiskLevel.NONE, SpamVerdict.levelFor(0f, SpamThreshold.MEDIUM))
    }

    @Test
    fun reasonCodesAreUniqueAndRoundTrip() {
        val codes = ScreeningReason.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        ScreeningReason.entries.forEach { assertEquals(it, ScreeningReason.fromCode(it.code)) }
        assertNull(ScreeningReason.fromCode("nope"))
        // TC-BLK-002 checks the stored reason "rule"; TC-BLK-010/011 check "error"/"timeout".
        assertEquals("rule", ScreeningReason.RULE.code)
        assertEquals("error", ScreeningReason.ERROR.code)
        assertEquals("timeout", ScreeningReason.TIMEOUT.code)
    }

    @Test
    fun callerInfoDefaultsToUnknownVerification() {
        assertEquals(CallerVerification.UNKNOWN, CallerInfo(number = null).verification)
    }
}
