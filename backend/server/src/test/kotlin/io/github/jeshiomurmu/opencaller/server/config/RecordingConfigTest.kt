package io.github.jeshiomurmu.opencaller.server.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The recording line's settings (ADR 0009): off by default, Bangladesh and India approved by default. */
class RecordingConfigTest {
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
    private val token = "a".repeat(32)

    @Test
    fun offByDefaultWithBangladeshAndIndiaApproved() {
        val recording = loadConfig(valid).recording
        assertFalse(recording.enabled)
        assertEquals(setOf("BD", "IN"), recording.countries)
        assertEquals(30, recording.downloadGraceMinutes)
        assertNull(recording.agentToken)
    }

    @Test
    fun linesAreMappedToTheirCountries() {
        val recording =
            loadConfig(valid + mapOf("RECORDING_LINES" to "+8809638000000, +918123456789", "RECORDING_AGENT_TOKEN" to token)).recording
        assertTrue(recording.enabled)
        assertEquals(mapOf("BD" to "+8809638000000", "IN" to "+918123456789"), recording.lines)
        assertFalse(recording.toString().contains(token))
    }

    @Test
    fun theOperatorSetsTheCountryList() {
        assertEquals(setOf("BD", "IN", "NP"), loadConfig(valid + ("RECORDING_COUNTRIES" to "bd, IN,np")).recording.countries)
        // Set but empty: recording is off everywhere.
        assertEquals(emptySet(), loadConfig(valid + ("RECORDING_COUNTRIES" to "")).recording.countries)
    }

    @Test
    fun mistakesAreListed() {
        val error =
            assertFailsWith<ConfigException> {
                loadConfig(
                    valid +
                        mapOf(
                            "RECORDING_LINES" to "+8809638000000,+8809638000001,01711000000",
                            "RECORDING_COUNTRIES" to "BD,Bangladesh",
                            "RECORDING_DOWNLOAD_GRACE_MINUTES" to "90",
                        ),
                )
            }
        val problems = error.problems.joinToString("\n")
        assertTrue("two numbers in BD" in problems, problems)
        assertTrue("must list E.164 numbers" in problems, problems)
        assertTrue("invalid country code: BANGLADESH" in problems, problems)
        assertTrue("RECORDING_DOWNLOAD_GRACE_MINUTES must be a number in 0..50" in problems, problems)
        assertTrue("RECORDING_AGENT_TOKEN is required" in problems, problems)
        assertTrue(
            assertFailsWith<ConfigException> { loadConfig(valid + ("RECORDING_AGENT_TOKEN" to "short")) }
                .problems
                .any { "at least 32" in it },
        )
    }
}
