package io.github.jeshiomurmu.opencaller.feature.dialer.search

/** What the dial pad accepts. */
object DialInput {
    /** Digits, the keypad's `*` and `#`, `+` for international numbers, and pauses (`,` wait 2 s, `;` wait). */
    const val ALLOWED: String = "0123456789*#+,;"

    /** The longest entry. */
    const val MAX_LENGTH: Int = 64

    /**
     * Pasted text as dial pad input: allowed characters are kept, letters become their keypad digits
     * ("1-800-FLOWERS" → "18003569377"), everything else (spaces, dashes, brackets) is dropped.
     */
    fun clean(text: String): String = text.mapNotNull(::dialable).joinToString("").take(MAX_LENGTH)

    private fun dialable(c: Char): Char? =
        when {
            c in ALLOWED -> c
            c.lowercaseChar() in 'a'..'z' -> T9Matcher().spell(c.toString()).single()
            else -> null
        }
}
