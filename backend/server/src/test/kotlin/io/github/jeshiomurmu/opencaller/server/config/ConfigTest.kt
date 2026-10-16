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
