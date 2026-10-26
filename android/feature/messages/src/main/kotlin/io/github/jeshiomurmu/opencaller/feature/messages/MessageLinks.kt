package io.github.jeshiomurmu.opencaller.feature.messages

import timber.log.Timber

/**
 * Reads `sms:`, `smsto:`, `mms:` and `mmsto:` links (RFC 5724): one or more recipients separated by commas (some
 * apps use semicolons), and an optional `?body=` text. Apps also pass the text as the `sms_body` extra.
 */
object MessageLinks {
    /** The schemes the default SMS app handles. */
    val SCHEMES: Set<String> = setOf("sms", "smsto", "mms", "mmsto")

    /** The `Intent` extra other apps put a prefilled text in. */
    const val EXTRA_SMS_BODY = "sms_body"

    /** The recipients of a link's scheme-specific part (`+15550001111,+15550002222?body=Hi`). */
    fun recipients(schemeSpecificPart: String?): List<String> =
        schemeSpecificPart
            .orEmpty()
            .substringBefore('?')
            .split(',', ';')
            .map { it.trim().removePrefix("//") }
            .filter(String::isNotEmpty)
            .distinct()

    /** The `body` query parameter of a scheme-specific part, if any. */
    fun body(schemeSpecificPart: String?): String? =
        schemeSpecificPart
            ?.substringAfter('?', "")
            ?.split('&')
            ?.firstOrNull { it.startsWith("body=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.let(::decode)
            ?.takeIf(String::isNotEmpty)

    // Percent-decoding as for URI query values ('+' stays a plus: phone links keep it literal).
    private fun decode(value: String): String =
        try {
            java.net.URLDecoder.decode(value.replace("+", "%2B"), UTF_8)
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Message link text kept as written")
            value
        }

    // A charset name, not a Charset: URLDecoder.decode(String, Charset) needs Android 13.
    private const val UTF_8 = "UTF-8"
}
