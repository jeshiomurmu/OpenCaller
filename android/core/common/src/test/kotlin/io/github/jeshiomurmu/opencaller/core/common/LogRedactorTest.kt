package io.github.jeshiomurmu.opencaller.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class LogRedactorTest {
    @Test
    fun masksE164KeepingPlusAndLastFourDigits() {
        assertEquals("call from +****5678", LogRedactor.redact("call from +8801712345678"))
    }

    @Test
    fun masksExactlySevenDigits() {
        assertEquals("****4567", LogRedactor.redact("1234567"))
    }

    @Test
    fun keepsRunsShorterThanSevenDigits() {
        assertEquals("id=123456 attempt 3", LogRedactor.redact("id=123456 attempt 3"))
    }

    @Test
    fun masksNumbersFormattedWithSpacesAndHyphens() {
        assertEquals("+****5678 and ****0123", LogRedactor.redact("+880 1712-345678 and 202-555-0123"))
    }

    @Test
    fun masksEveryNumberInTheMessage() {
        assertEquals("****5678 -> ****4321", LogRedactor.redact("01712345678 -> 09876544321"))
    }

    @Test
    fun leavesTimesAndTextAlone() {
        assertEquals("took 12:30:45, no number", LogRedactor.redact("took 12:30:45, no number"))
    }

    @Test
    fun handlesEmptyMessage() {
        assertEquals("", LogRedactor.redact(""))
    }
}
