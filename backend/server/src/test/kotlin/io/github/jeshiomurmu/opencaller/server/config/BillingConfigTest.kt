package io.github.jeshiomurmu.opencaller.server.config

import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Supporter-tier settings: self-hosted mode, Play billing from a service-account key file, the standard list's cap. */
class BillingConfigTest {
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

    private fun keyFile(tokenUri: String = "https://oauth2.googleapis.com/token"): String {
        val key =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
                .private
        val pem = "-----BEGIN PRIVATE KEY-----\n${Base64.getMimeEncoder().encodeToString(key.encoded)}\n-----END PRIVATE KEY-----\n"
        val json =
            """{"type":"service_account","private_key_id":"k1","private_key":${JsonPrimitive(pem)},""" +
                """"client_email":"billing@p.iam.gserviceaccount.com","token_uri":"$tokenUri"}"""
        return Files.createTempFile("service-account", ".json").also { Files.writeString(it, json) }.toString()
    }

    @Test
    fun billingIsOffByDefault() {
        val config = loadConfig(base)
        assertEquals(BillingConfig(selfHosted = false, googlePlay = null), config.billing)
        assertEquals(ListConfig.DEFAULT_STANDARD_MAX_ENTRIES, config.lists.standardMaxEntries)
    }

    @Test
    fun selfHostedAndTheListCapAreRead() {
        val config = loadConfig(base + mapOf("SELF_HOSTED" to "true", "LIST_STANDARD_MAX_ENTRIES" to "5000"))
        assertTrue(config.billing.selfHosted)
        assertEquals(5000, config.lists.standardMaxEntries)
        val error =
            assertFailsWith<ConfigException> { loadConfig(base + mapOf("SELF_HOSTED" to "yes", "LIST_STANDARD_MAX_ENTRIES" to "0")) }
        assertTrue(error.problems.any { "SELF_HOSTED must be true or false" in it }, error.message)
        assertTrue(error.problems.any { it.startsWith("LIST_STANDARD_MAX_ENTRIES") }, error.message)
    }

    @Test
    fun aServiceAccountKeyTurnsPlayBillingOnAndNeedsItsSettings() {
        val file = keyFile()
        val error = assertFailsWith<ConfigException> { loadConfig(base + mapOf("GOOGLE_PLAY_SERVICE_ACCOUNT_FILE" to file)) }
        assertTrue(error.problems.any { it.startsWith("GOOGLE_PLAY_PACKAGE_NAME") }, error.message)
        assertTrue(error.problems.any { it.startsWith("BILLING_ACCOUNT_SECRET") }, error.message)

        val config =
            loadConfig(
                base +
                    mapOf(
                        "GOOGLE_PLAY_SERVICE_ACCOUNT_FILE" to file,
                        "GOOGLE_PLAY_PACKAGE_NAME" to "io.github.jeshiomurmu.opencaller",
                        "BILLING_ACCOUNT_SECRET" to "b".repeat(32),
                    ),
            )
        val play = config.billing.googlePlay!!
        assertEquals("io.github.jeshiomurmu.opencaller", play.packageName)
        assertEquals(GooglePlayConfig.DEFAULT_PRODUCT_ID, play.productId)
        assertEquals(GooglePlayConfig.DEFAULT_API_BASE_URL, play.apiBaseUrl)
        assertEquals("billing@p.iam.gserviceaccount.com", play.serviceAccount.clientEmail)
        assertFalse("b".repeat(32) in config.toString())
        assertFalse("BEGIN PRIVATE KEY" in config.toString())
    }

    @Test
    fun anUnreadableOrInsecureKeyFileIsRefused() {
        val settings = mapOf("GOOGLE_PLAY_PACKAGE_NAME" to "io.github.jeshiomurmu.opencaller", "BILLING_ACCOUNT_SECRET" to "b".repeat(32))
        val missing =
            assertFailsWith<ConfigException> {
                loadConfig(base + settings + ("GOOGLE_PLAY_SERVICE_ACCOUNT_FILE" to "/nonexistent/key.json"))
            }
        assertTrue(missing.problems.any { it.startsWith("GOOGLE_PLAY_SERVICE_ACCOUNT_FILE can't be read") }, missing.message)
        val insecure =
            assertFailsWith<ConfigException> {
                loadConfig(
                    base + settings +
                        mapOf(
                            "ENV" to "production",
                            "OTP_PROVIDER" to "twilio",
                            "GOOGLE_PLAY_SERVICE_ACCOUNT_FILE" to keyFile("http://127.0.0.1/token"),
                        ),
                )
            }
        assertTrue(insecure.problems.any { "token_uri must use https" in it }, insecure.message)
        val badIds =
            assertFailsWith<ConfigException> {
                loadConfig(
                    base + settings +
                        mapOf(
                            "GOOGLE_PLAY_SERVICE_ACCOUNT_FILE" to keyFile(),
                            "GOOGLE_PLAY_PACKAGE_NAME" to "nodots",
                            "GOOGLE_PLAY_PRODUCT_ID" to "Upper",
                        ),
                )
            }
        assertTrue(badIds.problems.any { it.startsWith("GOOGLE_PLAY_PACKAGE_NAME must") }, badIds.message)
        assertTrue(badIds.problems.any { it.startsWith("GOOGLE_PLAY_PRODUCT_ID must") }, badIds.message)
        assertNull(loadConfig(base).billing.googlePlay)
    }
}
