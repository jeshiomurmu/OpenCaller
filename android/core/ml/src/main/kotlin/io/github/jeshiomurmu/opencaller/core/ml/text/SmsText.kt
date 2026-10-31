package io.github.jeshiomurmu.opencaller.core.ml.text

/** Text helpers shared by the extractors and the classifier. */
object SmsText {
    private const val RADIX = 10
    private val URL =
        Regex(
            """(?i)\b(?:https?://\S+|www\.\S+|(?:[\p{L}\p{N}](?:[\p{L}\p{N}-]{0,61}[\p{L}\p{N}])?\.)+""" +
                """(?:com|net|org|info|biz|io|co|me|ly|gl|gd|in|bd|us|uk|xyz|top|click|link|site|online|support|shop|live|app)""" +
                """(?:/\S*)?(?=$|[\s,;!?)]))""",
        )

    /**
     * Returns [text] with every Unicode decimal digit (Bengali ০–৯, Devanagari ०–९, …) replaced by its ASCII digit,
     * so codes and amounts written in either script are read the same way.
     */
    fun asciiDigits(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val digit = if (Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt()) Character.digit(cp, RADIX) else -1
            if (digit >= 0) out.append('0' + digit) else out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }

    /** The links in [text]: with a scheme, starting with `www.`, or a bare host on a common top-level domain. */
    fun links(text: String): List<String> = URL.findAll(text).map { it.value.trimEnd('.', ',', ':', ';', '!', '?') }.toList()

    /** [text] with its links replaced by a space, so digits in a link are never read as a code or an amount. */
    fun withoutLinks(text: String): String = URL.replace(text, " ")
}
