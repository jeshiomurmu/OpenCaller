package io.github.jeshiomurmu.opencaller.server.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** docs/architecture.md §10 invariant 3: logs redact numbers to the last 4 digits; logs are one JSON object per line. */
class JsonLogEncoderTest {
    private val context = LoggerContext()
    private val logger = context.getLogger("test")

    private fun encode(
        message: String,
        mdc: Map<String, String> = emptyMap(),
        error: Throwable? = null,
    ): Map<String, String> {
        val event = LoggingEvent("test", logger, Level.WARN, message, error, null)
        event.mdcPropertyMap = mdc
        val line = String(JsonLogEncoder().encode(event), Charsets.UTF_8)
        assertTrue(line.endsWith("\n") && line.count { it == '\n' } == 1, line)
        return Json.parseToJsonElement(line).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    @Test
    fun redactsNumbersLikeTheApp() {
        assertEquals("OTP for +****0123: 123456", LogRedactor.redact("OTP for +12015550123: 123456"))
        assertEquals("call from +****5678", LogRedactor.redact("call from +880 1712-345678"))
        assertEquals("code 2026 at 10:15, id 123456", LogRedactor.redact("code 2026 at 10:15, id 123456"))
    }

    @Test
    fun writesOneRedactedJsonObjectPerLine() {
        val fields = encode("Development OTP for +12015550123: 654321", mapOf("requestId" to "abc-1234567", "number" to "+12015550123"))
        assertEquals("WARN", fields["level"])
        assertEquals("test", fields["logger"])
        assertEquals("Development OTP for +****0123: 654321", fields["message"])
        assertEquals("abc-1234567", fields["requestId"])
        assertEquals("+****0123", fields["number"])
        assertTrue(fields["ts"]!!.endsWith("Z"))
    }

    @Test
    fun redactsStackTraces() {
        val fields = encode("failed", error = IllegalStateException("no user +12015550123"))
        assertTrue(fields["exception"]!!.contains("no user +****0123"), fields["exception"])
        assertTrue(!fields["exception"]!!.contains("2015550123"))
    }
}
