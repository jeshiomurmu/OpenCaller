package io.github.jeshiomurmu.opencaller.core.telephony.screening

import io.github.jeshiomurmu.opencaller.core.model.BlockRule
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleTarget
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleMatcherTest {
    private val normalizer = PhoneNumberNormalizer()
    private val matcher = RuleMatcher(normalizer::countryOf, TimeLimitedRegex(budgetMillis = 50))
    private val number = PhoneNumber("+15551230001")

    private fun rule(
        type: RuleType,
        pattern: String,
        action: RuleAction = RuleAction.BLOCK,
        id: Long = 1,
    ) = BlockRule(id, type, pattern, action, RuleTarget.CALLS, 0, enabled = true)

    @Test
    fun eachRuleType() {
        assertTrue(matcher.matches(rule(RuleType.EXACT, "+15551230001"), number))
        assertFalse(matcher.matches(rule(RuleType.EXACT, "+1555123000"), number))
        assertTrue(matcher.matches(rule(RuleType.PREFIX, "+1555123"), number))
        assertFalse(matcher.matches(rule(RuleType.PREFIX, "+1555124"), number))
        assertFalse(matcher.matches(rule(RuleType.PREFIX, ""), number))
        assertTrue(matcher.matches(rule(RuleType.COUNTRY, "us"), number))
        assertFalse(matcher.matches(rule(RuleType.COUNTRY, "CA"), number))
        assertTrue(matcher.matches(rule(RuleType.REGEX, """0001$"""), number))
        assertFalse(matcher.matches(rule(RuleType.REGEX, """^\+44"""), number))
    }

    @Test
    fun invalidRegexNeverMatches() {
        assertFalse(matcher.matches(rule(RuleType.REGEX, "(unclosed"), number))
    }

    @Test
    fun allowBeatsBlockBeatsSilence() {
        val silence = rule(RuleType.PREFIX, "+1", RuleAction.SILENCE, id = 1)
        val block = rule(RuleType.PREFIX, "+1555", RuleAction.BLOCK, id = 2)
        val allow = rule(RuleType.EXACT, number.e164, RuleAction.ALLOW, id = 3)
        assertEquals(allow, matcher.firstMatch(listOf(silence, block, allow), number))
        assertEquals(block, matcher.firstMatch(listOf(silence, block), number))
        assertEquals(silence, matcher.firstMatch(listOf(silence), number))
        assertNull(matcher.firstMatch(listOf(silence.copy(enabled = false)), number))
        assertNull(matcher.firstMatch(emptyList(), number))
    }

    // OpenJDK optimizes `(a+)+$`; `((a+)+)+b` is still exponential there (Android's ICU engine blows up on both).
    @Test
    fun tcBlk004CatastrophicPatternIsStoppedAtTheDeadline() {
        val regex = TimeLimitedRegex(budgetMillis = 50)
        val catastrophic = "((a+)+)+b"
        val input = "a".repeat(40) + "!"
        repeat(2) {
            val started = System.nanoTime()
            assertThrows(RegexTimeoutException::class.java) { regex.containsMatch(catastrophic, input) }
            val tookMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue("took $tookMillis ms", tookMillis < 1_000)
        }
        // After two strikes the pattern is skipped without running.
        val started = System.nanoTime()
        assertThrows(RegexTimeoutException::class.java) { regex.containsMatch(catastrophic, input) }
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10)
        // Other patterns still work.
        assertTrue(regex.containsMatch("a!$", input))
    }

    @Test
    fun watchdogStopsAPatternEvenWhenTheInputIsNotReadThroughCharSequence() {
        // Simulates Android's ICU engine: the deadline wrapper never fires (the clock stands still), the watchdog does.
        val regex = TimeLimitedRegex(budgetMillis = 50, nanoTime = { 0L })
        assertThrows(RegexTimeoutException::class.java) { regex.containsMatch("((a+)+)+b", "a".repeat(40) + "!") }
    }

    @Test
    fun deadlineCharSequenceBehavesLikeItsText() {
        val text = DeadlineCharSequence("+15551230001", deadlineNanos = Long.MAX_VALUE, nanoTime = { 0L })
        assertEquals(12, text.length)
        assertEquals('+', text[0])
        assertEquals("555", text.subSequence(2, 5).toString())
        assertEquals("+15551230001", text.toString())
        val expired = DeadlineCharSequence("abc", deadlineNanos = 10, nanoTime = { 11L })
        assertThrows(RegexTimeoutException::class.java) { expired[0] }
    }
}
