package io.github.jeshiomurmu.opencaller.core.ml.otp

/**
 * The origin-bound one-time code line (the WICG "Origin-bound one-time codes delivered via SMS" format): the
 * message's last line is `@top.example #code`, optionally followed by ` @embedded.example`. The code is meant only for
 * the site at [origin] (the first host).
 */
data class OriginBoundCode(
    val origin: String,
    val code: String,
    val embeddedOrigin: String?,
) {
    /** Never prints the code. */
    override fun toString(): String = "OriginBoundCode(origin=$origin, code=****, embeddedOrigin=$embeddedOrigin)"

    /** Parses the last line of a message. */
    companion object {
        private const val HOST = """[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+"""
        private val LINE = Regex("""^@($HOST) #([A-Za-z0-9]{4,8})(?: @($HOST))?$""")

        /** The code line at the end of [text], or `null` when the message doesn't end with one. */
        fun parse(text: String): OriginBoundCode? {
            val last = text.trimEnd().substringAfterLast('\n').trim()
            val match = LINE.matchEntire(last) ?: return null
            val (origin, code, embedded) = match.destructured
            return OriginBoundCode(origin.lowercase(), code, embedded.ifEmpty { null }?.lowercase())
        }
    }
}
