package io.github.jeshiomurmu.opencaller.core.telephony.screening

import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import java.util.regex.PatternSyntaxException

/** Why a rule pattern was rejected. */
enum class RuleProblem {
    /** Nothing was entered. */
    EMPTY,

    /** Too long to be a pattern for a phone number. */
    TOO_LONG,

    /** EXACT: not a valid phone number. */
    NOT_A_NUMBER,

    /** PREFIX: not `+` followed by 1–15 digits. */
    NOT_A_PREFIX,

    /** COUNTRY: not a known two-letter country code. */
    UNKNOWN_COUNTRY,

    /** REGEX: not a valid regular expression. */
    INVALID_REGEX,

    /** REGEX: too slow on test input (it could stall screening). */
    SLOW_REGEX,
}

/** Result of [RuleValidator.validate]. */
sealed interface RuleValidation {
    /** The pattern to store, normalized (E.164 for EXACT, no `*` for PREFIX, upper case for COUNTRY). */
    data class Valid(
        val pattern: String,
    ) : RuleValidation

    /** The input can't be used. */
    data class Invalid(
        val problem: RuleProblem,
    ) : RuleValidation
}

/**
 * Checks and normalizes a rule pattern before it is saved (TC-BLK-004): regular expressions must compile and finish
 * within [regex]'s budget on a set of hostile probe inputs, so a catastrophic pattern like `(a+)+$` is rejected in the
 * editor instead of being stored.
 */
class RuleValidator(
    private val normalizer: PhoneNumberNormalizer,
    private val regex: TimeLimitedRegex,
) {
    /** Validates [input] as a [type] pattern; national EXACT numbers are read in [homeRegion]. */
    fun validate(
        type: RuleType,
        input: String,
        homeRegion: String?,
    ): RuleValidation {
        val trimmed = input.trim()
        return when {
            trimmed.isEmpty() -> RuleValidation.Invalid(RuleProblem.EMPTY)
            trimmed.length > MAX_PATTERN_LENGTH -> RuleValidation.Invalid(RuleProblem.TOO_LONG)
            else -> validateNonEmpty(type, trimmed, homeRegion)
        }
    }

    private fun validateNonEmpty(
        type: RuleType,
        input: String,
        homeRegion: String?,
    ): RuleValidation =
        when (type) {
            RuleType.EXACT -> {
                normalizer
                    .normalize(input, homeRegion ?: UNKNOWN_REGION)
                    .getOrNull()
                    ?.let { RuleValidation.Valid(it.e164) }
                    ?: RuleValidation.Invalid(RuleProblem.NOT_A_NUMBER)
            }

            RuleType.PREFIX -> {
                val prefix = input.removeSuffix("*").filterNot { it == ' ' || it == '-' }
                if (PREFIX.matches(prefix)) RuleValidation.Valid(prefix) else RuleValidation.Invalid(RuleProblem.NOT_A_PREFIX)
            }

            RuleType.COUNTRY -> {
                val code = input.uppercase()
                if (code.length == 2 && normalizer.isSupportedRegion(code)) {
                    RuleValidation.Valid(code)
                } else {
                    RuleValidation.Invalid(RuleProblem.UNKNOWN_COUNTRY)
                }
            }

            RuleType.REGEX -> {
                validateRegex(input)
            }
        }

    private fun validateRegex(pattern: String): RuleValidation =
        try {
            PROBES.forEach { regex.containsMatch(pattern, it) }
            RuleValidation.Valid(pattern)
        } catch (_: PatternSyntaxException) {
            RuleValidation.Invalid(RuleProblem.INVALID_REGEX)
        } catch (_: RegexTimeoutException) {
            RuleValidation.Invalid(RuleProblem.SLOW_REGEX)
        }

    private companion object {
        const val MAX_PATTERN_LENGTH = 200
        const val UNKNOWN_REGION = "ZZ"
        val PREFIX = Regex("""^\+\d{1,15}$""")

        // Inputs that make backtracking patterns explode: runs of one character followed by a mismatch. 24 characters
        // (2^24 backtracking steps) exceed the budget on a phone, yet an abandoned run still ends within a second or so.
        const val RUN = 24
        val PROBES =
            listOf(
                "+12015550123",
                "+" + "1".repeat(15),
                "a".repeat(RUN) + "!",
                "1".repeat(RUN) + "!",
                "+" + "12".repeat(RUN / 2) + "x",
                "0".repeat(RUN) + "+",
            )
    }
}
