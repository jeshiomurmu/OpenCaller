package io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms

import java.util.Base64

/**
 * Reads MMS PDUs (OMA MMS Encapsulation 1.2, WAP WSP value encoding): download notices from the WAP push, downloaded
 * messages, send confirmations and delivery reports. Other message types return `null`.
 */
@Suppress("TooManyFunctions") // One reader per header group and part field.
object MmsPduParser {
    /**
     * The PDU in [bytes], or `null` for a message type OpenCaller doesn't handle.
     *
     * @throws MalformedPduException when [bytes] isn't a valid PDU.
     */
    fun parse(bytes: ByteArray): MmsPdu? {
        val reader = WspReader(bytes)
        val headers = readHeaders(reader)
        return when (headers.type) {
            MmsHeaders.TYPE_NOTIFICATION_IND -> notification(headers)
            MmsHeaders.TYPE_RETRIEVE_CONF -> retrieved(headers, reader)
            MmsHeaders.TYPE_SEND_CONF -> SendConf(headers.transactionId, headers.status ?: 0, headers.messageId)
            MmsHeaders.TYPE_DELIVERY_IND -> DeliveryInd(headers.messageId, headers.to.firstOrNull(), headers.status ?: 0)
            else -> null
        }
    }

    private fun notification(headers: Headers): NotificationInd =
        NotificationInd(
            transactionId = headers.transactionId,
            from = headers.from,
            subject = headers.subject,
            messageSize = headers.messageSize ?: 0,
            expiry = headers.expiry,
            contentLocation = headers.contentLocation ?: throw MalformedPduException("Notification without a location"),
        )

    private fun retrieved(
        headers: Headers,
        body: WspReader,
    ): RetrieveConf {
        val type = headers.contentType ?: throw MalformedPduException("Message without a content type")
        val parts =
            if (type.isMultipart) {
                readParts(body)
            } else {
                listOf(MmsPart(type.mediaType, body.rest(), type.charset, type.name))
            }
        return RetrieveConf(
            transactionId = headers.transactionId,
            messageId = headers.messageId,
            from = headers.from,
            to = headers.to,
            cc = headers.cc,
            date = headers.date,
            subject = headers.subject,
            contentType = type.mediaType,
            parts = parts,
        )
    }

    // Headers up to the end, or up to Content-Type, which is the last header before a body.
    private fun readHeaders(reader: WspReader): Headers {
        val headers = Headers()
        while (reader.hasMore && headers.contentType == null) {
            if (reader.peek() and WspReader.HIGH_BIT == 0) {
                // Application-header: Token-text name, then a text value.
                reader.text()
                reader.skipValue()
                continue
            }
            readHeader(reader.octet(), reader, headers)
        }
        return headers
    }

    @Suppress("CyclomaticComplexMethod") // One branch per header field the app reads.
    private fun readHeader(
        field: Int,
        reader: WspReader,
        headers: Headers,
    ) {
        when (field) {
            MmsHeaders.MESSAGE_TYPE -> headers.type = reader.octet()
            MmsHeaders.TRANSACTION_ID -> headers.transactionId = reader.text()
            MmsHeaders.MESSAGE_ID -> headers.messageId = reader.text()
            MmsHeaders.FROM -> headers.from = readFrom(reader)
            MmsHeaders.TO -> headers.to += address(reader.encodedString())
            MmsHeaders.CC -> headers.cc += address(reader.encodedString())
            MmsHeaders.SUBJECT -> headers.subject = reader.encodedString()
            MmsHeaders.DATE -> headers.date = reader.longInteger()
            MmsHeaders.MESSAGE_SIZE -> headers.messageSize = reader.longInteger()
            MmsHeaders.EXPIRY -> headers.expiry = readExpiry(reader)
            MmsHeaders.CONTENT_LOCATION -> headers.contentLocation = reader.text()
            MmsHeaders.RESPONSE_STATUS, MmsHeaders.STATUS -> headers.status = reader.octet()
            MmsHeaders.CONTENT_TYPE -> headers.contentType = readContentType(reader)
            else -> reader.skipValue()
        }
    }

    // From-value = Value-length (Address-present-token Encoded-string-value | Insert-address-token)
    private fun readFrom(reader: WspReader): String? {
        val value = reader.slice(reader.valueLength())
        return if (value.octet() == MmsHeaders.FROM_ADDRESS_PRESENT) address(value.encodedString()) else null
    }

    // Value-length (Absolute-token Date-value | Relative-token Delta-seconds-value); only absolute dates are kept.
    private fun readExpiry(reader: WspReader): Long? {
        val value = reader.slice(reader.valueLength())
        val token = value.octet()
        val seconds = value.longInteger()
        return seconds.takeIf { token == MmsHeaders.ABSOLUTE_TOKEN }
    }

    private fun readParts(reader: WspReader): List<MmsPart> {
        val count = reader.uintvar().toInt()
        return List(count) {
            val headersLength = reader.uintvar().toInt()
            val dataLength = reader.uintvar().toInt()
            val headers = reader.slice(headersLength)
            val data = reader.bytes(dataLength)
            readPart(headers, data)
        }
    }

    private fun readPart(
        headers: WspReader,
        data: ByteArray,
    ): MmsPart {
        val type = readContentType(headers)
        val partHeaders = PartHeaders()
        while (headers.hasMore) readPartHeader(headers, partHeaders)
        val decoded = if (partHeaders.transferEncoding.equals(BASE64, ignoreCase = true)) Base64.getMimeDecoder().decode(data) else data
        return MmsPart(
            contentType = type.mediaType,
            data = decoded,
            charset = type.charset,
            name = type.name,
            filename = partHeaders.filename,
            contentId = partHeaders.contentId?.removeSurrounding("<", ">"),
            contentLocation = partHeaders.contentLocation,
        )
    }

    private fun readPartHeader(
        reader: WspReader,
        part: PartHeaders,
    ) {
        if (reader.peek() and WspReader.HIGH_BIT == 0) {
            // A textual header such as "Content-Transfer-Encoding: base64".
            val name = reader.text()
            val isText = !reader.nextIsValueLength() && reader.peek() and WspReader.HIGH_BIT == 0
            val value = if (isText) reader.text() else null
            if (!isText) reader.skipValue()
            if (name.equals(TRANSFER_ENCODING, ignoreCase = true)) part.transferEncoding = value
            return
        }
        when (reader.octet()) {
            CONTENT_LOCATION -> part.contentLocation = reader.text()
            CONTENT_ID -> part.contentId = reader.quotedOrText()
            CONTENT_DISPOSITION, DEPRECATED_CONTENT_DISPOSITION -> part.filename = readDisposition(reader)
            else -> reader.skipValue()
        }
    }

    // Value-length Disposition (token or text) *(Parameter); returns the filename parameter.
    private fun readDisposition(reader: WspReader): String? {
        val value = reader.slice(reader.valueLength())
        if (!value.hasMore) return null
        if (value.peek() and WspReader.HIGH_BIT != 0) value.octet() else value.text()
        return readParameters(value).filename
    }

    private const val BASE64 = "base64"
    private const val TRANSFER_ENCODING = "Content-Transfer-Encoding"
    private const val CONTENT_LOCATION = 0x8E
    private const val CONTENT_ID = 0xC0
    private const val DEPRECATED_CONTENT_DISPOSITION = 0xAE
    private const val CONTENT_DISPOSITION = 0xC5
}

private class Headers {
    var type: Int? = null
    var transactionId: String? = null
    var messageId: String? = null
    var from: String? = null
    val to = mutableListOf<String>()
    val cc = mutableListOf<String>()
    var subject: String? = null
    var date: Long? = null
    var messageSize: Long? = null
    var expiry: Long? = null
    var contentLocation: String? = null
    var status: Int? = null
    var contentType: ContentTypeValue? = null
}

private class PartHeaders {
    var contentLocation: String? = null
    var contentId: String? = null
    var filename: String? = null
    var transferEncoding: String? = null
}

/** An address as shown and stored: without the `/TYPE=PLMN` suffix of phone numbers. */
internal fun address(encoded: String): String =
    encoded.trim().removeSuffix(MmsHeaders.PLMN_SUFFIX).removeSuffix(MmsHeaders.PLMN_SUFFIX.lowercase())
