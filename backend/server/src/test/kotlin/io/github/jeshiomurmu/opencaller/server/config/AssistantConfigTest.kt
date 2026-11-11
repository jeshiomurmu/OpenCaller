package io.github.jeshiomurmu.opencaller.server.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The call assistant's settings (ADR 0010): no numbers by default, 100 minutes a month, 5 a call. */
class AssistantConfigTest {
    private val valid =
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
    private val agentsAndLiveKit =
        mapOf(
            "RECORDING_AGENT_TOKEN" to "a".repeat(32),
            "LIVEKIT_URL" to "ws://livekit:7880/",
            "LIVEKIT_API_KEY" to "opencaller",
            "LIVEKIT_API_SECRET" to "s".repeat(32),
        )

    @Test
    fun noNumbersByDefault() {
        val assistant = loadConfig(valid).assistant
        assertEquals(emptyMap(), assistant.numbers)
        assertEquals(100, assistant.monthlyMinutes)
        assertEquals(5, assistant.maxCallMinutes)
    }

    @Test
    fun numbersAreMappedToTheirCountriesAndTheCapIsTheOperators() {
        val assistant =
            loadConfig(
                valid + agentsAndLiveKit +
                    mapOf(
                        "ASSISTANT_NUMBERS" to "+8809638000001, +8809638000002,+918123456789",
                        "ASSISTANT_MONTHLY_MINUTES" to "0",
                        "ASSISTANT_MAX_CALL_MINUTES" to "3",
                    ),
            ).assistant
        assertEquals(mapOf("+8809638000001" to "BD", "+8809638000002" to "BD", "+918123456789" to "IN"), assistant.numbers)
        assertEquals(setOf("BD", "IN"), assistant.countries)
        assertEquals(0, assistant.monthlyMinutes) // no cap
        assertEquals(3, assistant.maxCallMinutes)
    }

    @Test
    fun mistakesAreListed() {
        val error =
            assertFailsWith<ConfigException> {
                loadConfig(
                    valid +
                        mapOf(
                            "ASSISTANT_NUMBERS" to "+8809638000001,+8809638000001,01711000000",
                            "ASSISTANT_MONTHLY_MINUTES" to "-1",
                            "ASSISTANT_MAX_CALL_MINUTES" to "60",
                        ),
                )
            }
        val problems = error.problems.joinToString("\n")
        assertTrue("ASSISTANT_NUMBERS lists a number twice" in problems, problems)
        assertTrue("ASSISTANT_NUMBERS must list E.164 numbers" in problems, problems)
        assertTrue("ASSISTANT_MONTHLY_MINUTES must be a number in 0..100000" in problems, problems)
        assertTrue("ASSISTANT_MAX_CALL_MINUTES must be a number in 1..30" in problems, problems)
        assertTrue("RECORDING_AGENT_TOKEN is required when ASSISTANT_NUMBERS is set" in problems, problems)
        assertTrue("LIVEKIT_URL, LIVEKIT_API_KEY and LIVEKIT_API_SECRET are required when ASSISTANT_NUMBERS is set" in problems, problems)
    }
}
