package io.github.jeshiomurmu.opencaller.server.auth.otp

import io.github.jeshiomurmu.opencaller.server.auth.E164
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Issues and checks sign-in codes: 6 digits, valid for [validFor] (5 minutes), at most [maxAttempts] (5) wrong
 * tries. Codes are stored as HMAC-SHA256(pepper, number + code), so a copy of Redis alone can't be brute-forced
 * back to codes.
 */
class OtpService(
    private val store: OtpStore,
    private val provider: OtpProvider,
    pepper: String,
    private val random: SecureRandom,
    val validFor: Duration = 5.minutes,
    val maxAttempts: Int = 5,
) {
    private val key = SecretKeySpec(pepper.toByteArray(Charsets.UTF_8), HMAC)

    /** Creates a code for [number], stores its hash and sends it. If sending fails the code is removed again. */
    suspend fun start(number: E164) {
        val code = (0 until CODE_LENGTH).joinToString("") { random.nextInt(DIGITS).toString() }
        store.put(number, hash(number, code), validFor)
        try {
            provider.send(number, code, validFor)
        } catch (e: OtpDeliveryException) {
            store.delete(number)
            throw e
        }
    }

    /** Checks [code] for [number]. */
    suspend fun verify(
        number: E164,
        code: String,
    ): OtpCheck = store.check(number, hash(number, code), maxAttempts)

    private fun hash(
        number: E164,
        code: String,
    ): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(key)
        return mac.doFinal("${number.value}:$code".toByteArray(Charsets.UTF_8)).toHexString()
    }

    /** Code format. */
    companion object {
        /** Digits in a code. */
        const val CODE_LENGTH = 6
        private const val DIGITS = 10
        private const val HMAC = "HmacSHA256"

        /** `true` if [code] has the shape of a code (exactly six ASCII digits). */
        fun isWellFormed(code: String?): Boolean = code != null && code.length == CODE_LENGTH && code.all { it in '0'..'9' }
    }
}
