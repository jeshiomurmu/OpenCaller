package io.github.jeshiomurmu.opencaller.core.common

/**
 * Removes phone numbers from log messages (docs/architecture.md §10, invariant 3).
 *
 * Any run of at least [MIN_DIGITS] digits, optionally split by single spaces or hyphens (as in formatted
 * numbers like `+880 1712-345678`), is replaced by `****` followed by its last [VISIBLE_DIGITS] digits.
 * A leading `+` is kept. The mask has a fixed length so the original length is not revealed either.
 */
object LogRedactor {
    /** Shortest digit run that is treated as a phone number. */
    const val MIN_DIGITS = 7

    /** Digits kept visible at the end of a redacted run. */
    const val VISIBLE_DIGITS = 4

    private const val MASK = "****"
    private val digitRun = Regex("""\d(?:[ -]?\d)+""")

    /** Returns [message] with every phone-number-like digit run redacted. */
    fun redact(message: String): String =
        digitRun.replace(message) { match ->
            val digits = match.value.filter(Char::isDigit)
            if (digits.length >= MIN_DIGITS) MASK + digits.takeLast(VISIBLE_DIGITS) else match.value
        }
}
