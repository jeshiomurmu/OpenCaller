package io.github.jeshiomurmu.opencaller.server.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.encoder.EncoderBase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * Removes phone numbers from log text (docs/architecture.md §10, invariant 3) with the same rule as the app's
 * `LogRedactor`: a run of at least 7 digits, optionally split by single spaces or hyphens, becomes `****` plus
 * its last 4 digits; a leading `+` stays.
 */
object LogRedactor {
    private const val MIN_DIGITS = 7
    private const val VISIBLE_DIGITS = 4
    private const val MASK = "****"
    private val digitRun = Regex("""\d(?:[ -]?\d)+""")

    /** Returns [text] with every phone-number-like digit run redacted. */
    fun redact(text: String): String =
        digitRun.replace(text) { match ->
            val digits = match.value.filter(Char::isDigit)
            if (digits.length >= MIN_DIGITS) MASK + digits.takeLast(VISIBLE_DIGITS) else match.value
        }
}

/**
 * One JSON object per line: `ts`, `level`, `logger`, `thread`, `message`, `requestId` (from the MDC, set by the
 * CallId plugin), other MDC entries and `exception`. Message, MDC values and stack traces go through
 * [LogRedactor]; the request ID and timestamp are written as they are.
 */
class JsonLogEncoder : EncoderBase<ILoggingEvent>() {
    override fun headerBytes(): ByteArray? = null

    override fun footerBytes(): ByteArray? = null

    override fun encode(event: ILoggingEvent): ByteArray {
        val fields = LinkedHashMap<String, JsonPrimitive>()
        fields["ts"] = JsonPrimitive(Instant.ofEpochMilli(event.timeStamp).toString())
        fields["level"] = JsonPrimitive(event.level.toString())
        fields["logger"] = JsonPrimitive(event.loggerName)
        fields["thread"] = JsonPrimitive(event.threadName)
        fields["message"] = JsonPrimitive(LogRedactor.redact(event.formattedMessage.orEmpty()))
        event.mdcPropertyMap.orEmpty().toSortedMap().forEach { (key, value) ->
            fields[key] = JsonPrimitive(if (key == REQUEST_ID) value else LogRedactor.redact(value))
        }
        event.throwableProxy?.let { fields["exception"] = JsonPrimitive(LogRedactor.redact(ThrowableProxyUtil.asString(it))) }
        return (JsonObject(fields).toString() + "\n").toByteArray(Charsets.UTF_8)
    }

    /** MDC keys. */
    companion object {
        /** MDC key of the request ID (also the `X-Request-Id` header). */
        const val REQUEST_ID = "requestId"
    }
}
