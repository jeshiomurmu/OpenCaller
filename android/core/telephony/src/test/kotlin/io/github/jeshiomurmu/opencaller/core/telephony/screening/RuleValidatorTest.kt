package io.github.jeshiomurmu.opencaller.core.telephony.screening

import io.github.jeshiomurmu.opencaller.core.model.RuleType
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleValidatorTest {
    private val validator = RuleValidator(PhoneNumberNormalizer(), TimeLimitedRegex(budgetMillis = 50))

    private fun valid(pattern: String) = RuleValidation.Valid(pattern)

    private fun invalid(problem: RuleProblem) = RuleValidation.Invalid(problem)

    @Test
    fun exactNumbersAreNormalizedToE164() {
        assertEquals(valid("+8801712345678"), validator.validate(RuleType.EXACT, " 01712-345678 ", "BD"))
        assertEquals(valid("+12015550123"), validator.validate(RuleType.EXACT, "+1 201 555 0123", null))
        assertEquals(invalid(RuleProblem.NOT_A_NUMBER), validator.validate(RuleType.EXACT, "12345", "US"))
        assertEquals(invalid(RuleProblem.EMPTY), validator.validate(RuleType.EXACT, "   ", "US"))
        assertEquals(invalid(RuleProblem.TOO_LONG), validator.validate(RuleType.EXACT, "1".repeat(201), "US"))
    }

    @Test
    fun prefixesDropTheTrailingStar() {
        // TC-BLK-003's rule as the user types it.
        assertEquals(valid("+1555123"), validator.validate(RuleType.PREFIX, "+1555123*", "US"))
        assertEquals(valid("+1900"), validator.validate(RuleType.PREFIX, "+1 900", "US"))
        assertEquals(invalid(RuleProblem.NOT_A_PREFIX), validator.validate(RuleType.PREFIX, "1555", "US"))
        assertEquals(invalid(RuleProblem.NOT_A_PREFIX), validator.validate(RuleType.PREFIX, "+", "US"))
    }

    @Test
    fun countriesAreKnownRegionCodes() {
        assertEquals(valid("NG"), validator.validate(RuleType.COUNTRY, "ng", "US"))
        assertEquals(invalid(RuleProblem.UNKNOWN_COUNTRY), validator.validate(RuleType.COUNTRY, "XX", "US"))
        assertEquals(invalid(RuleProblem.UNKNOWN_COUNTRY), validator.validate(RuleType.COUNTRY, "Nigeria", "US"))
    }

    @Test
    fun tcBlk004EditorRejectsCatastrophicRegex() {
        // `(a+)+$` is rejected on Android (ICU backtracks exponentially); OpenJDK optimizes it, so test the variant
        // that is exponential on both.
        assertEquals(invalid(RuleProblem.SLOW_REGEX), validator.validate(RuleType.REGEX, "((a+)+)+b", "US"))
        assertEquals(invalid(RuleProblem.SLOW_REGEX), validator.validate(RuleType.REGEX, """((\d+)+)+x""", "US"))
        assertEquals(invalid(RuleProblem.INVALID_REGEX), validator.validate(RuleType.REGEX, "(+", "US"))
        assertEquals(valid("""^\+1(900|976)"""), validator.validate(RuleType.REGEX, """^\+1(900|976)""", "US"))
    }
}
