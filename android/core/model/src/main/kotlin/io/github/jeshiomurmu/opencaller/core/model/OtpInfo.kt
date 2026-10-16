package io.github.jeshiomurmu.opencaller.core.model

/**
 * A one-time password extracted from a message on the device (never uploaded).
 *
 * @property code the code itself.
 * @property sender who sent the message, if known.
 * @property origin domain the code is bound to (`@example.com #123456`), if the message declares one.
 * @property expiresAtMillis expiry time in epoch milliseconds, if stated in the message.
 * @property appHash SMS Retriever app hash in the message, if any.
 */
data class OtpInfo(
    val code: String,
    val sender: Address? = null,
    val origin: String? = null,
    val expiresAtMillis: Long? = null,
    val appHash: String? = null,
) {
    init {
        require(code.isNotBlank()) { "code must not be blank" }
    }

    /** Never prints the code, so it can't leak into logs. */
    override fun toString(): String = "OtpInfo(code=****, origin=$origin, expiresAtMillis=$expiresAtMillis)"
}
