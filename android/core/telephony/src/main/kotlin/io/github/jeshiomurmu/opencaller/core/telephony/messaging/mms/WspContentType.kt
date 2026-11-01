package io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms

/** A Content-Type value with the parameters the app uses. */
internal data class ContentTypeValue(
    val mediaType: String,
    val charset: Int? = null,
    val name: String? = null,
    val filename: String? = null,
    val start: String? = null,
    val type: String? = null,
) {
    /** True for a multipart body (the WAP multipart types and the plain MIME multipart types). */
    val isMultipart: Boolean
        get() =
            mediaType.startsWith("application/vnd.wap.multipart.", ignoreCase = true) ||
                mediaType.startsWith("multipart/", ignoreCase = true)
}

/** Parameter tokens (WSP Assigned Numbers), without the high bit. */
internal object WspParameters {
    const val CHARSET = 0x01
    const val TYPE = 0x03
    const val DEPRECATED_NAME = 0x05
    const val DEPRECATED_FILENAME = 0x06
    const val MULTIPART_RELATED_TYPE = 0x09
    const val DEPRECATED_START = 0x0A
    const val NAME = 0x17
    const val FILENAME = 0x18
    const val START = 0x19
}

private const val UNKNOWN_TYPE = "application/octet-stream"

/**
 * Content-type-value = Constrained-media | Content-general-form, where Content-general-form = Value-length
 * Media-type *(Parameter) (WAP-230 §8.4.2.24).
 */
internal fun readContentType(reader: WspReader): ContentTypeValue {
    val first = reader.peek()
    return when {
        first and WspReader.HIGH_BIT != 0 -> {
            ContentTypeValue(wellKnown(reader.shortInteger().toLong()))
        }

        first > WspReader.LENGTH_QUOTE -> {
            ContentTypeValue(reader.text())
        }

        first == 0 -> {
            ContentTypeValue(reader.text().ifEmpty { UNKNOWN_TYPE })
        }

        else -> {
            val value = reader.slice(reader.valueLength())
            val media = value.peek()
            val mediaType =
                if (media > WspReader.LENGTH_QUOTE && media and WspReader.HIGH_BIT == 0) value.text() else wellKnown(value.integer())
            readParameters(value).copy(mediaType = mediaType)
        }
    }
}

/** Reads the parameters left in [reader] (a content type or a content disposition). */
internal fun readParameters(reader: WspReader): ContentTypeValue {
    var result = ContentTypeValue(UNKNOWN_TYPE)
    while (reader.hasMore) {
        val first = reader.peek()
        result =
            if (first and WspReader.HIGH_BIT != 0 || first <= WspReader.MAX_SHORT_LENGTH) {
                typedParameter(reader.integer().toInt(), reader, result)
            } else {
                untypedParameter(reader.text(), reader, result)
            }
    }
    return result
}

private fun typedParameter(
    token: Int,
    reader: WspReader,
    current: ContentTypeValue,
): ContentTypeValue =
    when (token) {
        WspParameters.CHARSET -> current.copy(charset = readCharset(reader))
        WspParameters.TYPE, WspParameters.MULTIPART_RELATED_TYPE -> current.copy(type = readConstrainedType(reader))
        WspParameters.NAME, WspParameters.DEPRECATED_NAME -> current.copy(name = reader.quotedOrText())
        WspParameters.FILENAME, WspParameters.DEPRECATED_FILENAME -> current.copy(filename = reader.quotedOrText())
        WspParameters.START, WspParameters.DEPRECATED_START -> current.copy(start = reader.quotedOrText())
        else -> current.also { reader.skipValue() }
    }

// Untyped-parameter = Token-text Untyped-value, where the value is an Integer-value or a Text-value.
private fun untypedParameter(
    name: String,
    reader: WspReader,
    current: ContentTypeValue,
): ContentTypeValue {
    val isText = reader.peek() > WspReader.MAX_SHORT_LENGTH && reader.peek() and WspReader.HIGH_BIT == 0
    if (!isText) {
        val number = reader.integer().toInt()
        return if (name.equals("charset", ignoreCase = true)) current.copy(charset = number) else current
    }
    val value = reader.quotedOrText()
    return when (name.lowercase()) {
        "charset" -> current.copy(charset = MmsCharsets.mibEnumOf(value))
        "name" -> current.copy(name = value)
        "filename" -> current.copy(filename = value)
        "start" -> current.copy(start = value)
        "type" -> current.copy(type = value)
        else -> current
    }
}

// Well-known-charset (Any-charset or an Integer-value) or a charset name as text.
private fun readCharset(reader: WspReader): Int? {
    val first = reader.peek()
    return if (first > WspReader.MAX_SHORT_LENGTH && first and WspReader.HIGH_BIT == 0) {
        MmsCharsets.mibEnumOf(reader.text())
    } else {
        reader.integer().toInt()
    }
}

// Constrained-encoding = Extension-media | Short-integer
private fun readConstrainedType(reader: WspReader): String =
    if (reader.peek() and WspReader.HIGH_BIT !=
        0
    ) {
        wellKnown(reader.shortInteger().toLong())
    } else {
        reader.text()
    }

private fun wellKnown(code: Long): String = WellKnownContentTypes.typeOf(code.toInt()) ?: UNKNOWN_TYPE
