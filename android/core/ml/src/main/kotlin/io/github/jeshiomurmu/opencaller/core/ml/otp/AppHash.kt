package io.github.jeshiomurmu.opencaller.core.ml.otp

/**
 * The SMS Retriever app hash: 11 characters of `[A-Za-z0-9+/]` that end a verification message, so that one app can
 * read its own codes. Messages carrying one usually start with `<#>`.
 */
object AppHash {
    private const val LENGTH = 11
    private val TOKEN = Regex("""^[A-Za-z0-9+/]{$LENGTH}$""")

    // An ordinary word ("information", "Information") is not a hash.
    private val WORD = Regex("""^[A-Z]?[a-z]+$""")

    /**
     * The hash at the end of [text], or `null`. It must be the last token and stand on its own line or follow a
     * message that starts with `<#>`.
     */
    fun find(text: String): String? {
        val trimmed = text.trim()
        val token = trimmed.substringAfterLast('\n').substringAfterLast(' ').trim()
        val ownLine = trimmed.contains('\n') && trimmed.substringAfterLast('\n').trim() == token
        val marked = trimmed.startsWith("<#>")
        return token.takeIf { TOKEN.matches(it) && !WORD.matches(it) && (ownLine || marked) }
    }
}
