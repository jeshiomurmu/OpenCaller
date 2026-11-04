package io.github.jeshiomurmu.opencaller.server.push

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.github.jeshiomurmu.opencaller.server.config.VapidConfig
import java.net.URI
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.time.Clock
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Web Push message encryption, RFC 8291 (`aes128gcm`, RFC 8188), as UnifiedPush's connector decrypts it (Tink's
 * `apps-webpush`): an ephemeral P-256 key agrees with the subscription's key; HKDF-SHA256 derives the IKM with the
 * subscription's auth secret and `"WebPush: info" 0x00 || ua_public || as_public`, then the content key
 * (`"Content-Encoding: aes128gcm" 0x00`, 16 bytes) and nonce (`"Content-Encoding: nonce" 0x00`, 12 bytes) with a random
 * 16-byte salt. The body is one record: salt || rs (4 bytes) || 65 || as_public || AES-GCM(plaintext || 0x02).
 */
object WebPushCrypto {
    private const val SALT_BYTES = 16
    private const val AUTH_SECRET_BYTES = 16
    private const val IKM_BYTES = 32
    private const val KEY_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val RECORD_SIZE = 4096
    private const val PUBLIC_KEY_BYTES = 65
    private const val PADDING_DELIMITER: Byte = 2

    /** Largest plaintext that fits the single 4096-byte record (header and tag excluded). */
    const val MAX_PLAINTEXT_BYTES = RECORD_SIZE - 1 - TAG_BITS / Byte.SIZE_BITS
    private val IKM_INFO = "WebPush: info".toByteArray() + 0
    private val KEY_INFO = "Content-Encoding: aes128gcm".toByteArray() + 0
    private val NONCE_INFO = "Content-Encoding: nonce".toByteArray() + 0

    /** Encrypts [plaintext] for the subscription key [uaPublic] (65 bytes, uncompressed) and [authSecret] (16 bytes). */
    fun encrypt(
        plaintext: ByteArray,
        uaPublic: ByteArray,
        authSecret: ByteArray,
        random: SecureRandom,
    ): ByteArray {
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "push payload too large" }
        require(authSecret.size == AUTH_SECRET_BYTES) { "auth secret must be 16 bytes" }
        val recipient = VapidConfig.publicKeyOf(uaPublic)
        val ephemeral =
            KeyPairGenerator.getInstance("EC").apply { initialize(VapidConfig.P256, random) }.generateKeyPair()
        val asPublic = VapidConfig.uncompressed(ephemeral.public as ECPublicKey)
        val shared =
            KeyAgreement.getInstance("ECDH").run {
                init(ephemeral.private)
                doPhase(recipient, true)
                generateSecret()
            }
        val ikm = hkdf(salt = authSecret, ikm = shared, info = IKM_INFO + uaPublic + asPublic, length = IKM_BYTES)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val key = hkdf(salt, ikm, KEY_INFO, KEY_BYTES)
        val nonce = hkdf(salt, ikm, NONCE_INFO, NONCE_BYTES)
        val ciphertext =
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                doFinal(plaintext + PADDING_DELIMITER)
            }
        return ByteBuffer
            .allocate(SALT_BYTES + Int.SIZE_BYTES + 1 + PUBLIC_KEY_BYTES + ciphertext.size)
            .put(salt)
            .putInt(RECORD_SIZE)
            .put(PUBLIC_KEY_BYTES.toByte())
            .put(asPublic)
            .put(ciphertext)
            .array()
    }

    // HKDF-SHA256 (RFC 5869) for outputs of at most one block.
    private fun hkdf(
        salt: ByteArray,
        ikm: ByteArray,
        info: ByteArray,
        length: Int,
    ): ByteArray {
        val prk = hmac(salt, ikm)
        return hmac(prk, info + 1).copyOf(length)
    }

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}

/**
 * VAPID (RFC 8292): an ES256 JWT with `aud` = the push service's origin, `exp` (12 hours) and `sub` = the server's
 * contact, sent as `Authorization: vapid t=<jwt>, k=<public key>`.
 */
class VapidSigner(
    private val config: VapidConfig,
    private val clock: Clock,
) {
    private val algorithm = Algorithm.ECDSA256(config.publicKey, config.privateKey)

    /** The `Authorization` header value for a push to [endpoint]. */
    fun authorization(endpoint: URI): String {
        val origin = "${endpoint.scheme}://${endpoint.rawAuthority}"
        val token =
            JWT
                .create()
                .withAudience(origin)
                .withExpiresAt(Date.from(clock.instant().plusSeconds(LIFETIME_SECONDS)))
                .withSubject(config.subject)
                .sign(algorithm)
        return "vapid t=$token, k=${config.publicKeyBase64Url}"
    }

    private companion object {
        const val LIFETIME_SECONDS = 12L * 60 * 60
    }
}
