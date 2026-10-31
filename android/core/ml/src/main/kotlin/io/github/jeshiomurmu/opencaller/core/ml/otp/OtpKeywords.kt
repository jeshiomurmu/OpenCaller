package io.github.jeshiomurmu.opencaller.core.ml.otp

/**
 * Words that mark a one-time code, in English, Bengali and Hindi. A number is read as a code only in a message
 * that has one of them (or an origin-bound line or an app hash), and the number nearest to one wins.
 */
internal object OtpKeywords {
    private val ENGLISH =
        Regex(
            """(?i)\b(?:otp|one[- ]time (?:password|passcode|code|pin)|pass ?code|password|pin|codes?|verification|verify|2fa)\b""",
        )

    // Bengali and Hindi: whole words are matched by substring (word boundaries don't hold for Indic scripts).
    private val OTHERS =
        listOf(
            // Bengali: OTP, code, PIN, verification (two spellings), verify, password
            "ওটিপি",
            "কোড",
            "পিন",
            "ভেরিফিকেশন",
            "যাচাইকরণ",
            "যাচাই",
            "পাসওয়ার্ড",
            // Hindi: OTP, code, PIN, verification, password, one time
            "ओटीपी",
            "कोड",
            "पिन",
            "सत्यापन",
            "पासवर्ड",
            "वन टाइम",
        )

    /** Where the keywords are in [text], as index ranges. */
    fun find(text: String): List<IntRange> {
        val english = ENGLISH.findAll(text).map { it.range }
        val others =
            OTHERS.asSequence().flatMap { word ->
                generateSequence(text.indexOf(word).takeIf { it >= 0 }) { from ->
                    text.indexOf(word, from + word.length).takeIf { it >= 0 }
                }.map { it until it + word.length }
            }
        return (english + others).toList()
    }
}
