package io.github.jeshiomurmu.opencaller.core.model

/**
 * A non-dialable message sender: an alphanumeric header (`AD-HDFCBK`, `Google`) or a short code (`12345`).
 * Stored trimmed, as received; 1–[MAX_LENGTH] printable characters.
 */
@JvmInline
value class SenderId(
    val value: String,
) : Address {
    init {
        require(value.isNotBlank() && value == value.trim()) { "SenderId must be non-blank and trimmed" }
        require(value.length <= MAX_LENGTH) { "SenderId longer than $MAX_LENGTH characters" }
        require(value.none(Char::isISOControl)) { "SenderId contains control characters" }
    }

    /** Length limits for sender IDs. */
    companion object {
        /** Longest accepted sender ID (alphanumeric headers are at most 11 characters; prefixed forms are longer). */
        const val MAX_LENGTH = 20
    }
}
