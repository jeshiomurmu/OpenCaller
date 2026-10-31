package io.github.jeshiomurmu.opencaller.core.ml.otp

/**
 * Finds the numbers in a message that could be a one-time code: 4 to 8 digits standing on their own, not an amount,
 * card or account digits, a phone number, a date, a time or a percentage. The text has ASCII digits only.
 */
internal object CodeCandidates {
    private const val MIN_DIGITS = 4
    private const val MAX_DIGITS = 8
    private const val CONTEXT = 24
    private val DIGITS = Regex("""\d+""")
    private val CURRENCY_BEFORE = Regex("""(?i)(?:(?:^|[^\p{L}])(?:rs\.?|inr|usd|bdt|tk\.?|taka)|[₹$৳]|रु\.?|টাকা)\s*$""")
    private val CURRENCY_AFTER = Regex("""(?i)^\s*(?:taka\b|tk\b|rupees\b|dollars\b|টাকা|रुपये)""")
    private val ACCOUNT_BEFORE =
        Regex("""(?i)(?:ending(?: in)?|a/c(?: no\.?)?|acct|account|card|no\.|कार्ड|खाते|কার্ড|অ্যাকাউন্ট)\s*[:#]?\s*[x*]*$""")
    private val MONTH_BEFORE = Regex("""(?i)\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[-/ ]$""")
    private const val JOINERS = "-/.,:"

    /** The candidate codes in [text], as index ranges. */
    fun find(text: String): List<IntRange> =
        DIGITS
            .findAll(text)
            .map { it.range }
            .filter { it.last - it.first + 1 in MIN_DIGITS..MAX_DIGITS }
            .filterNot { standsInsideSomething(text, it) || hasNumberContext(text, it) }
            .toList()

    // Glued to letters (XX1234, SAVE20, an ID), part of a longer number (1,250.00, 1800-555-0100, 12/10) or a percentage.
    private fun standsInsideSomething(
        text: String,
        range: IntRange,
    ): Boolean {
        val before = text.getOrNull(range.first - 1)
        val after = text.getOrNull(range.last + 1)
        val joinedBefore = before != null && before in JOINERS && text.getOrNull(range.first - 2)?.isDigit() == true
        val joinedAfter = after != null && after in JOINERS && text.getOrNull(range.last + 2)?.isDigit() == true
        return before?.isLetter() == true || after?.isLetter() == true || joinedBefore || joinedAfter || after == '%'
    }

    // An amount (Rs. 1250, 1250 taka), card or account digits (ending 1234), a date (Oct-2026) or a phone number (+91 98…).
    private fun hasNumberContext(
        text: String,
        range: IntRange,
    ): Boolean {
        val before = text.substring(maxOf(0, range.first - CONTEXT), range.first)
        val after = text.substring(range.last + 1, minOf(text.length, range.last + 1 + CONTEXT))
        return CURRENCY_BEFORE.containsMatchIn(before) ||
            CURRENCY_AFTER.containsMatchIn(after) ||
            ACCOUNT_BEFORE.containsMatchIn(before) ||
            MONTH_BEFORE.containsMatchIn(before) ||
            isPhoneNumber(text, range.first)
    }

    // Digits, spaces and hyphens back to a "+": part of an international number.
    private fun isPhoneNumber(
        text: String,
        start: Int,
    ): Boolean {
        var i = start - 1
        while (i >= 0 && isPhonePart(text[i])) i--
        return i >= 0 && text[i] == '+'
    }

    private fun isPhonePart(c: Char): Boolean = c.isDigit() || c == ' ' || c == '-'
}
