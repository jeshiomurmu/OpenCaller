package io.github.jeshiomurmu.opencaller.server.config

import io.github.jeshiomurmu.opencaller.server.auth.otp.LogOtpProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Configuration comes only from environment variables and fails fast, listing every problem. */
class ConfigTest {
    private val valid =
        mapOf(
            "ENV" to "development",
            "DB_URL" to "jdbc:postgresql://localhost:5432/opencaller",
            "DB_USER" to "opencaller",
            "DB_PASSWORD" to "secret",
            "REDIS_URL" to "redis://:secret@localhost:6379",
            "MINIO_ENDPOINT" to "http://localhost:9000",
            "MINIO_ACCESS_KEY" to "opencaller",
            "MINIO_SECRET_KEY" to "secret-secret",
            "JWT_SECRET" to "j".repeat(32),
            "OTP_PEPPER" to "p".repeat(32),
            "OTP_PROVIDER" to "log",
        )

    @Test
    fun aCompleteEnvironmentLoadsWithDefaults() {
        val config = loadConfig(valid)
        assertEquals(Environment.DEVELOPMENT, config.environment)
        assertEquals(8080, config.http.port)
        assertEquals(9090, config.http.metricsPort)
        assertEquals("dev", config.version)
        assertEquals(10, config.database.maxPoolSize)
        assertEquals("opencaller", config.jwt.issuer)
        assertEquals(OtpProviderKind.LOG, config.otp.provider)
        assertNull(config.twilio)
        assertEquals(MinioConfig("http://localhost:9000", "opencaller", "secret-secret", "opencaller", "us-east-1"), config.minio)
        assertEquals(ScoringConfig(15, 1.0, 2.0, 30.0, 0.39), config.scoring)
        assertEquals(ListConfig(3, 0.4), config.lists)
        assertNull(config.admin.token)
        // Secrets never show up in toString (e.g. in a log line).
        assertTrue(!config.toString().contains("secret-secret") && !config.toString().contains("j".repeat(32)))
    }

    @Test
    fun theRedisPasswordIsOptionalAndNeverPrinted() {
        assertNull(loadConfig(valid).redis.password)
        val config = loadConfig(valid + mapOf("REDIS_URL" to "redis://redis:6379", "REDIS_PASSWORD" to "redis-secret-value"))
        assertEquals("redis-secret-value", config.redis.password)
        assertTrue(!config.redis.toString().contains("redis-secret-value"))
    }

    @Test
    fun everyMissingVariableIsListed() {
        val error = assertFailsWith<ConfigException> { loadConfig(emptyMap()) }
        val expected =
            listOf(
                "ENV",
                "DB_URL",
                "DB_USER",
                "DB_PASSWORD",
                "REDIS_URL",
                "MINIO_ENDPOINT",
                "MINIO_ACCESS_KEY",
                "MINIO_SECRET_KEY",
                "JWT_SECRET",
                "OTP_PEPPER",
                "OTP_PROVIDER",
            )
        expected.forEach { name -> assertTrue(error.problems.any { it.startsWith(name) }, "$name missing from ${error.problems}") }
    }

    @Test
    fun blankValuesCountAsMissing() {
        val error = assertFailsWith<ConfigException> { loadConfig(valid + ("DB_PASSWORD" to "  ")) }
        assertEquals(listOf("DB_PASSWORD is required"), error.problems)
    }

    @Test
    fun invalidValuesAreRejected() {
        val error =
            assertFailsWith<ConfigException> {
                loadConfig(
                    valid +
                        mapOf(
                            "ENV" to "staging",
                            "DB_URL" to "postgres://x",
                            "REDIS_URL" to "localhost:6379",
                            "JWT_SECRET" to "short",
                            "PORT" to "70000",
                            "METRICS_PORT" to "abc",
                        ),
                )
            }
        listOf(
            "ENV must be one of",
            "DB_URL must start with",
            "REDIS_URL must start with",
            "JWT_SECRET must be at least 32",
            "PORT must be",
            "METRICS_PORT must be",
        ).forEach { prefix -> assertTrue(error.problems.any { it.startsWith(prefix) }, "$prefix not in ${error.problems}") }
    }

    @Test
    fun scoringListsAndAdminSettingsAreReadAndChecked() {
        val config =
            loadConfig(
                valid +
                    mapOf(
                        "MINIO_BUCKET" to "spam-lists",
                        "MINIO_REGION" to "eu-west-1",
                        "SCORING_INTERVAL_MINUTES" to "5",
                        "SCORING_PRIOR_SPAM" to "2",
                        "SCORING_PRIOR_NOT_SPAM" to "8.5",
                        "SCORING_HALF_LIFE_DAYS" to "14",
                        "SCORING_VERIFIED_CAP" to "0.3",
                        "LIST_BUILD_HOUR_UTC" to "0",
                        "LIST_MIN_SCORE" to "0.5",
                        "ADMIN_TOKEN" to "a".repeat(32),
                    ),
            )
        assertEquals("spam-lists", config.minio.bucket)
        assertEquals("eu-west-1", config.minio.region)
        assertEquals(ScoringConfig(5, 2.0, 8.5, 14.0, 0.3), config.scoring)
        assertEquals(ListConfig(0, 0.5), config.lists)
        assertEquals("a".repeat(32), config.admin.token)
        assertTrue(!config.toString().contains("a".repeat(32)))

        val error =
            assertFailsWith<ConfigException> {
                loadConfig(
                    valid +
                        mapOf(
                            "MINIO_BUCKET" to "Bad_Bucket",
                            "SCORING_PRIOR_SPAM" to "0",
                            "SCORING_HALF_LIFE_DAYS" to "abc",
                            "LIST_BUILD_HOUR_UTC" to "24",
                            "ADMIN_TOKEN" to "short",
                        ),
                )
            }
        listOf(
            "MINIO_BUCKET must be a valid",
            "SCORING_PRIOR_SPAM must be",
            "SCORING_HALF_LIFE_DAYS must be",
            "LIST_BUILD_HOUR_UTC must be",
            "ADMIN_TOKEN must be at least 32",
        ).forEach { prefix -> assertTrue(error.problems.any { it.startsWith(prefix) }, "$prefix not in ${error.problems}") }
        val cap = assertFailsWith<ConfigException> { loadConfig(valid + ("SCORING_VERIFIED_CAP" to "0.5")) }
        assertEquals(listOf("SCORING_VERIFIED_CAP must be below LIST_MIN_SCORE"), cap.problems)
    }

    @Test
    fun productionRefusesTheLogOtpProvider() {
        val error = assertFailsWith<ConfigException> { loadConfig(valid + ("ENV" to "production")) }
        assertTrue(error.problems.single().contains("development only"))
        assertFailsWith<IllegalArgumentException> { LogOtpProvider(Environment.PRODUCTION) }
        LogOtpProvider(Environment.DEVELOPMENT)
    }

    @Test
    fun twilioNeedsCredentialsAndExactlyOneSender() {
        val twilio = valid + ("OTP_PROVIDER" to "twilio")
        val missing = assertFailsWith<ConfigException> { loadConfig(twilio) }.problems
        assertTrue(missing.any { it.startsWith("TWILIO_ACCOUNT_SID") } && missing.any { it.startsWith("TWILIO_AUTH_TOKEN") })
        assertTrue(missing.any { it.contains("exactly one of TWILIO_FROM and TWILIO_MESSAGING_SERVICE_SID") })

        val both =
            twilio +
                mapOf(
                    "TWILIO_ACCOUNT_SID" to "AC123",
                    "TWILIO_AUTH_TOKEN" to "t",
                    "TWILIO_FROM" to "+15005550006",
                    "TWILIO_MESSAGING_SERVICE_SID" to "MG1",
                )
        assertTrue(assertFailsWith<ConfigException> { loadConfig(both) }.problems.single().contains("exactly one"))

        val ok = loadConfig(both - "TWILIO_MESSAGING_SERVICE_SID" + ("ENV" to "production"))
        assertEquals("+15005550006", ok.twilio!!.from)
        assertEquals("https://api.twilio.com", ok.twilio.baseUrl)
        assertTrue(!ok.twilio.toString().contains("authToken=t,"))
    }
}
