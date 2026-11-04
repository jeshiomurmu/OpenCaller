package io.github.jeshiomurmu.opencaller.server.config

import kotlinx.serialization.json.JsonPrimitive
import java.math.BigInteger
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Phase 11 settings: LiveKit, FCM and VAPID are each off until configured, and checked when they are. */
class VoipConfigTest {
    private val base =
        mapOf(
            "ENV" to "development",
            "DB_URL" to "jdbc:postgresql://localhost:5432/opencaller",
            "DB_USER" to "opencaller",
            "DB_PASSWORD" to "secret",
            "REDIS_URL" to "redis://localhost:6379",
            "MINIO_ENDPOINT" to "http://localhost:9000",
            "MINIO_ACCESS_KEY" to "opencaller",
            "MINIO_SECRET_KEY" to "secret-secret",
            "JWT_SECRET" to "j".repeat(32),
            "OTP_PEPPER" to "p".repeat(32),
            "OTP_PROVIDER" to "log",
        )
    private val liveKit =
        mapOf("LIVEKIT_URL" to "ws://livekit:7880/", "LIVEKIT_API_KEY" to "opencaller", "LIVEKIT_API_SECRET" to "s".repeat(32))
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private fun vapidPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(VapidConfig.P256) }.generateKeyPair()

    private fun vapidEnv(
        public: KeyPair,
        private: KeyPair = public,
    ): Map<String, String> {
        val d =
            (private.private as ECPrivateKey)
                .s
                .toByteArray()
                .dropWhile { it == 0.toByte() }
                .toByteArray()
        return mapOf(
            "VAPID_PUBLIC_KEY" to b64.encodeToString(VapidConfig.uncompressed(public.public as ECPublicKey)),
            "VAPID_PRIVATE_KEY" to b64.encodeToString(ByteArray(32 - d.size) + d),
            "VAPID_SUBJECT" to "mailto:ops@example.org",
        )
    }

    private fun keyFile(): String {
        val key =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
                .private
        val pem = "-----BEGIN PRIVATE KEY-----\n${Base64.getMimeEncoder().encodeToString(key.encoded)}\n-----END PRIVATE KEY-----\n"
        val json =
            """{"type":"service_account","private_key_id":"k1","private_key":${JsonPrimitive(pem)},""" +
                """"client_email":"push@p.iam.gserviceaccount.com","token_uri":"https://oauth2.googleapis.com/token"}"""
        return Files.createTempFile("service-account", ".json").also { Files.writeString(it, json) }.toString()
    }

    @Test
    fun everythingIsOffByDefault() {
        val voip = loadConfig(base).voip
        assertNull(voip.liveKit)
        assertFalse(voip.push.enabled)
        assertEquals(VideoConfig(), voip.video)
    }

    @Test
    fun liveKitIsReadAndChecked() {
        val config = assertNotNull(loadConfig(base + liveKit).voip.liveKit)
        assertEquals("ws://livekit:7880", config.url)
        assertEquals("opencaller", config.apiKey)
        assertEquals(LiveKitConfig.DEFAULT_TOKEN_TTL_SECONDS, config.tokenTtlSeconds)

        val error =
            assertFailsWith<ConfigException> {
                loadConfig(
                    base + liveKit + mapOf("LIVEKIT_API_SECRET" to "short", "LIVEKIT_API_KEY" to "a b", "LIVEKIT_TOKEN_TTL_SECONDS" to "5"),
                )
            }
        assertTrue(error.problems.any { it.startsWith("LIVEKIT_API_SECRET") }, error.message)
        assertTrue(error.problems.any { it.startsWith("LIVEKIT_API_KEY") }, error.message)
        assertTrue(error.problems.any { it.startsWith("LIVEKIT_TOKEN_TTL_SECONDS") }, error.message)
        val http = assertFailsWith<ConfigException> { loadConfig(base + liveKit + mapOf("LIVEKIT_URL" to "http://livekit:7880")) }
        assertTrue(http.problems.any { it.startsWith("LIVEKIT_URL") }, http.message)
        val production = assertFailsWith<ConfigException> { loadConfig(base + liveKit + mapOf("ENV" to "production")) }
        assertTrue(production.problems.any { "LIVEKIT_URL must use wss:// in production" in it }, production.message)
    }

    @Test
    fun aVapidKeyPairTurnsOnWebPush() {
        val pair = vapidPair()
        val vapid = assertNotNull(loadConfig(base + vapidEnv(pair)).voip.push.webPush)
        assertEquals(87, vapid.publicKeyBase64Url.length) // what the UnifiedPush connector expects
        assertEquals(
            BigInteger.ZERO,
            vapid.publicKey.w.affineX
                .subtract((pair.public as ECPublicKey).w.affineX),
        )

        val mismatch = assertFailsWith<ConfigException> { loadConfig(base + vapidEnv(pair, vapidPair())) }
        assertTrue(mismatch.problems.any { "not one key pair" in it }, mismatch.message)
        val broken = assertFailsWith<ConfigException> { loadConfig(base + vapidEnv(pair) + mapOf("VAPID_PUBLIC_KEY" to "AAAA")) }
        assertTrue(broken.problems.any { it.startsWith("VAPID_PUBLIC_KEY") }, broken.message)
        val noPrivate = assertFailsWith<ConfigException> { loadConfig(base + vapidEnv(pair).minus("VAPID_PRIVATE_KEY")) }
        assertTrue(noPrivate.problems.any { it.startsWith("VAPID_PRIVATE_KEY") }, noPrivate.message)
    }

    @Test
    fun fcmNeedsAServiceAccountAndAProject() {
        val fcm =
            assertNotNull(
                loadConfig(base + mapOf("FCM_SERVICE_ACCOUNT_FILE" to keyFile(), "FCM_PROJECT_ID" to "opencaller-app")).voip.push.fcm,
            )
        assertEquals("opencaller-app", fcm.projectId)
        assertEquals("https://fcm.googleapis.com", fcm.apiBaseUrl)
        val error = assertFailsWith<ConfigException> { loadConfig(base + mapOf("FCM_SERVICE_ACCOUNT_FILE" to keyFile())) }
        assertTrue(error.problems.any { it.startsWith("FCM_PROJECT_ID") }, error.message)
        val missing =
            assertFailsWith<ConfigException> {
                loadConfig(base + mapOf("FCM_SERVICE_ACCOUNT_FILE" to "/nope.json", "FCM_PROJECT_ID" to "opencaller-app"))
            }
        assertTrue(missing.problems.any { it.startsWith("FCM_SERVICE_ACCOUNT_FILE") }, missing.message)
    }
}
