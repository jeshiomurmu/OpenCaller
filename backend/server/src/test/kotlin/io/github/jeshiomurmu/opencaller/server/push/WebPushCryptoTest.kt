package io.github.jeshiomurmu.opencaller.server.push

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier.BaseVerification
import com.auth0.jwt.algorithms.Algorithm
import com.google.crypto.tink.apps.webpush.WebPushHybridDecrypt
import io.github.jeshiomurmu.opencaller.server.config.VapidConfig
import io.github.jeshiomurmu.opencaller.server.testing.MutableClock
import java.net.URI
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Web Push encryption checked against Google's Tink `apps-webpush` (the decryption UnifiedPush's connector uses). */
class WebPushCryptoTest {
    private val random = SecureRandom()

    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply { initialize(VapidConfig.P256, random) }.generateKeyPair()

    @Test
    fun tinkDecryptsWhatTheServerEncrypts() {
        val subscriber = keyPair()
        val publicKey = VapidConfig.uncompressed(subscriber.public as ECPublicKey)
        val auth = ByteArray(16).also(random::nextBytes)
        val payload = """{"id":"0d4f5c46-3a5e-4f43-9a4b-2f2a7f4c1b0e"}""".toByteArray()

        val body = WebPushCrypto.encrypt(payload, publicKey, auth, random)

        val decrypt =
            WebPushHybridDecrypt
                .Builder()
                .withAuthSecret(auth)
                .withRecipientPublicKey(publicKey)
                .withRecipientPrivateKey(subscriber.private as ECPrivateKey)
                .build()
        assertContentEquals(payload, decrypt.decrypt(body, null))
        // Header: 16-byte salt, record size 4096, key length 65, the server's ephemeral key.
        assertEquals(
            4096,
            java.nio.ByteBuffer
                .wrap(body, 16, 4)
                .int,
        )
        assertEquals(65, body[20].toInt())
    }

    @Test
    fun eachMessageUsesAFreshKeyAndSalt() {
        val subscriber = VapidConfig.uncompressed(keyPair().public as ECPublicKey)
        val auth = ByteArray(16).also(random::nextBytes)
        val a = WebPushCrypto.encrypt("x".toByteArray(), subscriber, auth, random)
        val b = WebPushCrypto.encrypt("x".toByteArray(), subscriber, auth, random)
        assertTrue(!a.contentEquals(b))
        assertFailsWith<IllegalArgumentException> { WebPushCrypto.encrypt(ByteArray(5000), subscriber, auth, random) }
    }

    @Test
    fun vapidHeaderCarriesASignedTokenForThePushServicesOrigin() {
        val pair = keyPair()
        val raw =
            (pair.private as ECPrivateKey)
                .s
                .toByteArray()
                .dropWhile { it == 0.toByte() }
                .toByteArray()
        val privateKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32 - raw.size) + raw)
        val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(VapidConfig.uncompressed(pair.public as ECPublicKey))
        val config = VapidConfig.parse(publicKey, privateKey, "mailto:ops@example.org")
        val clock = MutableClock()
        val header = VapidSigner(config, clock).authorization(URI("https://push.example.org:8443/up/abc?x=1"))

        val token = header.substringAfter("vapid t=").substringBefore(", k=")
        assertEquals(publicKey, header.substringAfter(", k="))
        val verified = (JWT.require(Algorithm.ECDSA256(pair.public as ECPublicKey, null)) as BaseVerification).build(clock).verify(token)
        assertEquals(listOf("https://push.example.org:8443"), verified.audience)
        assertEquals("mailto:ops@example.org", verified.subject)
        assertEquals("ES256", verified.algorithm)
    }

    @Test
    fun vapidKeysThatDontBelongTogetherAreRefused() {
        val a = keyPair()
        val b = keyPair()
        val raw =
            (b.private as ECPrivateKey)
                .s
                .toByteArray()
                .dropWhile { it == 0.toByte() }
                .toByteArray()
        val privateKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32 - raw.size) + raw)
        val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(VapidConfig.uncompressed(a.public as ECPublicKey))
        assertFailsWith<IllegalArgumentException> { VapidConfig.parse(publicKey, privateKey, "mailto:ops@example.org") }
    }
}
