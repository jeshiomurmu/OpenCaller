package io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms

/** A PDU that isn't valid MMS encoding (truncated, or a value of the wrong form). */
class MalformedPduException(
    message: String,
) : Exception(message)

/**
 * Reads the value encodings of WAP WSP (WAP-230 §8.4) that the MMS encapsulation uses: short and long integers,
 * uintvars, value lengths, text and quoted strings and encoded string values.
 */
@Suppress("TooManyFunctions") // One function per WSP value encoding.
internal class WspReader(
    private val bytes: ByteArray,
    start: Int = 0,
    private val end: Int = bytes.size,
) {
    /** The position of the next byte. */
    var position: Int = start
        private set

    /** True while bytes are left. */
    val hasMore: Boolean get() = position < end

    /** The next byte (0–255) without reading it. */
    fun peek(): Int = if (position < end) bytes[position].toInt() and BYTE_MASK else fail("end of data")

    /** Reads one byte (0–255). */
    fun octet(): Int = peek().also { position++ }

    /** Reads [count] bytes. */
    fun bytes(count: Int): ByteArray {
        if (count < 0 || position + count > end) fail("$count bytes past the end")
        return bytes.copyOfRange(position, position + count).also { position += count }
    }

    /** Reads all bytes that are left. */
    fun rest(): ByteArray = bytes(end - position)

    /** Skips [count] bytes. */
    fun skip(count: Int) {
        if (count < 0 || position + count > end) fail("skip past the end")
        position += count
    }

    /** A reader over the next [length] bytes, which are skipped here. */
    fun slice(length: Int): WspReader {
        if (length < 0 || position + length > end) fail("value of $length bytes past the end")
        return WspReader(bytes, position, position + length).also { position += length }
    }

    /** Short-integer: one byte with the high bit set. */
    fun shortInteger(): Int {
        val value = octet()
        if (value and HIGH_BIT == 0) fail("short integer expected")
        return value and LOW_SEVEN
    }

    /** Long-integer: a short length (0–30) and that many big-endian bytes. */
    fun longInteger(): Long {
        val length = octet()
        if (length > MAX_SHORT_LENGTH || length > Long.SIZE_BYTES) fail("long integer of $length bytes")
        var value = 0L
        repeat(length) { value = (value shl Byte.SIZE_BITS) or octet().toLong() }
        return value
    }

    /** Integer-value: a short or a long integer. */
    fun integer(): Long = if (peek() and HIGH_BIT != 0) shortInteger().toLong() else longInteger()

    /** Uintvar: 7 bits per byte, the high bit marking that more follow. */
    fun uintvar(): Long {
        var value = 0L
        repeat(MAX_UINTVAR_BYTES) {
            val next = octet()
            value = (value shl UINTVAR_BITS) or (next and LOW_SEVEN).toLong()
            if (next and HIGH_BIT == 0) return value
        }
        fail("uintvar longer than $MAX_UINTVAR_BYTES bytes")
    }

    /** Value-length: a short length (0–30), or 31 followed by a uintvar. */
    fun valueLength(): Int {
        val first = octet()
        return when {
            first <= MAX_SHORT_LENGTH -> first
            first == LENGTH_QUOTE -> uintvar().toInt()
            else -> fail("value length expected, got $first")
        }
    }

    /** True when the next byte starts a value-length (a general form value). */
    fun nextIsValueLength(): Boolean = peek() <= LENGTH_QUOTE

    /** Text-string: an optional quote byte (127), the text and a NUL; returned as raw bytes. */
    fun textBytes(): ByteArray {
        if (peek() == QUOTE) position++
        val stop = (position until end).firstOrNull { bytes[it].toInt() == 0 } ?: fail("text without its end")
        return bytes.copyOfRange(position, stop).also { position = stop + 1 }
    }

    /** Text-string as ISO-8859-1 text (header tokens, URLs, IDs). */
    fun text(): String = String(textBytes(), Charsets.ISO_8859_1)

    /** Quoted-string (a `"` then text) or a plain text-string. */
    fun quotedOrText(): String {
        if (peek() == DOUBLE_QUOTE) position++
        return text()
    }

    /** Encoded-string-value: a text-string, or a value length, a character set and a text-string. */
    fun encodedString(): String {
        // A lone NUL is an empty text-string, not a value length of 0.
        if (!nextIsValueLength() || peek() == 0) return MmsCharsets.decode(textBytes(), MmsCharsets.UTF_8)
        val value = slice(valueLength())
        val charset = value.integer().toInt()
        return MmsCharsets.decode(value.textBytes(), charset)
    }

    /**
     * Skips one value of unknown type: a short integer, a value-length prefixed value, or a text-string.
     */
    fun skipValue() {
        val first = peek()
        when {
            first and HIGH_BIT != 0 -> position++
            first <= LENGTH_QUOTE -> skip(valueLength())
            else -> textBytes()
        }
    }

    private fun fail(reason: String): Nothing = throw MalformedPduException("Bad MMS PDU at byte $position: $reason")

    companion object {
        const val HIGH_BIT: Int = 0x80
        const val LOW_SEVEN: Int = 0x7F
        const val BYTE_MASK: Int = 0xFF
        const val MAX_SHORT_LENGTH: Int = 30
        const val LENGTH_QUOTE: Int = 31
        const val QUOTE: Int = 127
        const val DOUBLE_QUOTE: Int = 0x22
        const val UINTVAR_BITS: Int = 7
        const val MAX_UINTVAR_BYTES: Int = 5
    }
}
