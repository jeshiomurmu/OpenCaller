package io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms

import java.io.ByteArrayOutputStream

/**
 * Writes the MMS PDUs OpenCaller sends (OMA MMS Encapsulation 1.2): a message, and the answers to a download notice.
 */
object MmsPduComposer {
    /** The bytes of [pdu]. */
    fun compose(pdu: MmsPdu): ByteArray =
        WspWriter()
            .apply {
                when (pdu) {
                    is SendReq -> sendReq(pdu)
                    is NotifyRespInd -> notifyResp(pdu)
                    is AcknowledgeInd -> acknowledge(pdu)
                    else -> throw IllegalArgumentException("OpenCaller doesn't send ${pdu::class.simpleName}")
                }
            }.toByteArray()

    private fun WspWriter.start(
        type: Int,
        transactionId: String,
    ) {
        octet(MmsHeaders.MESSAGE_TYPE)
        octet(type)
        octet(MmsHeaders.TRANSACTION_ID)
        text(transactionId.toByteArray(Charsets.ISO_8859_1))
        octet(MmsHeaders.MMS_VERSION)
        shortInteger(MmsHeaders.VERSION_1_2)
    }

    private fun WspWriter.sendReq(pdu: SendReq) {
        start(MmsHeaders.TYPE_SEND_REQ, pdu.transactionId)
        // The centre inserts the sender's own number.
        octet(MmsHeaders.FROM)
        valueLength(1)
        octet(MmsHeaders.FROM_INSERT_ADDRESS)
        pdu.to.forEach {
            octet(MmsHeaders.TO)
            encodedString(addressForHeader(it))
        }
        pdu.subject?.takeIf(String::isNotEmpty)?.let {
            octet(MmsHeaders.SUBJECT)
            encodedString(it)
        }
        octet(MmsHeaders.MESSAGE_CLASS)
        octet(MmsHeaders.MESSAGE_CLASS_PERSONAL)
        octet(MmsHeaders.DELIVERY_REPORT)
        octet(if (pdu.deliveryReport) MmsHeaders.VALUE_YES else MmsHeaders.VALUE_NO)
        octet(MmsHeaders.READ_REPORT)
        octet(MmsHeaders.VALUE_NO)
        octet(MmsHeaders.CONTENT_TYPE)
        multipartContentType(pdu.parts)
        body(pdu.parts)
    }

    private fun WspWriter.notifyResp(pdu: NotifyRespInd) {
        start(MmsHeaders.TYPE_NOTIFYRESP_IND, pdu.transactionId)
        octet(MmsHeaders.STATUS)
        octet(pdu.status)
    }

    private fun WspWriter.acknowledge(pdu: AcknowledgeInd) = start(MmsHeaders.TYPE_ACKNOWLEDGE_IND, pdu.transactionId)

    // multipart/related with the SMIL part as its start when there is one, else multipart/mixed.
    private fun WspWriter.multipartContentType(parts: List<MmsPart>) {
        val smil = parts.firstOrNull { it.contentType.equals(MmsPart.APPLICATION_SMIL, ignoreCase = true) }
        block {
            if (smil == null) {
                shortInteger(checkNotNull(WellKnownContentTypes.codeOf(WellKnownContentTypes.MULTIPART_MIXED)))
            } else {
                shortInteger(checkNotNull(WellKnownContentTypes.codeOf(WellKnownContentTypes.MULTIPART_RELATED)))
                shortInteger(WspParameters.DEPRECATED_START)
                text("<${smil.contentId}>".toByteArray(Charsets.ISO_8859_1))
                shortInteger(WspParameters.MULTIPART_RELATED_TYPE)
                text(MmsPart.APPLICATION_SMIL.toByteArray(Charsets.ISO_8859_1))
            }
        }
    }

    // Multipart: uintvar count, then per part: uintvar headers length, uintvar data length, headers, data.
    private fun WspWriter.body(parts: List<MmsPart>) {
        uintvar(parts.size.toLong())
        parts.forEach { part ->
            val headers = WspWriter().apply { partHeaders(part) }.toByteArray()
            uintvar(headers.size.toLong())
            uintvar(part.data.size.toLong())
            bytes(headers)
            bytes(part.data)
        }
    }

    private fun WspWriter.partHeaders(part: MmsPart) {
        block {
            val code = WellKnownContentTypes.codeOf(part.contentType)
            if (code != null) shortInteger(code) else text(part.contentType.toByteArray(Charsets.ISO_8859_1))
            part.charset?.let {
                shortInteger(WspParameters.CHARSET)
                integer(it.toLong())
            }
            part.name?.let {
                shortInteger(WspParameters.DEPRECATED_NAME)
                text(it.toByteArray(Charsets.UTF_8))
            }
        }
        part.contentId?.let {
            octet(CONTENT_ID)
            quoted("<$it>".toByteArray(Charsets.ISO_8859_1))
        }
        part.contentLocation?.let {
            octet(CONTENT_LOCATION)
            text(it.toByteArray(Charsets.ISO_8859_1))
        }
    }

    // Phone numbers carry /TYPE=PLMN; e-mail addresses don't.
    private fun addressForHeader(address: String): String =
        if ('@' in
            address
        ) {
            address
        } else {
            address.filter { it.isDigit() || it == '+' } + MmsHeaders.PLMN_SUFFIX
        }

    private const val CONTENT_LOCATION = 0x8E
    private const val CONTENT_ID = 0xC0
}

/** Writes the WSP value encodings (the counterpart of [WspReader]). */
@Suppress("TooManyFunctions") // One function per WSP value encoding.
internal class WspWriter {
    private val out = ByteArrayOutputStream()

    fun toByteArray(): ByteArray = out.toByteArray()

    fun octet(value: Int) = out.write(value and WspReader.BYTE_MASK)

    fun bytes(value: ByteArray) = out.write(value)

    fun shortInteger(value: Int) {
        require(value in 0..WspReader.LOW_SEVEN) { "short integer out of range: $value" }
        octet(value or WspReader.HIGH_BIT)
    }

    fun longInteger(value: Long) {
        require(value >= 0) { "negative long integer" }
        val bytes =
            generateSequence(value) {
                (it ushr Byte.SIZE_BITS).takeIf { rest ->
                    rest > 0
                }
            }.map { (it and WspReader.BYTE_MASK.toLong()).toInt() }.toList().reversed()
        octet(bytes.size)
        bytes.forEach(::octet)
    }

    fun integer(value: Long) = if (value in 0..WspReader.LOW_SEVEN.toLong()) shortInteger(value.toInt()) else longInteger(value)

    fun uintvar(value: Long) {
        require(value >= 0) { "negative uintvar" }
        val groups =
            generateSequence(value) {
                (it ushr WspReader.UINTVAR_BITS).takeIf { rest ->
                    rest > 0
                }
            }.map { (it and WspReader.LOW_SEVEN.toLong()).toInt() }.toList().reversed()
        groups.forEachIndexed { index, group -> octet(if (index < groups.lastIndex) group or WspReader.HIGH_BIT else group) }
    }

    fun valueLength(length: Int) {
        if (length <= WspReader.MAX_SHORT_LENGTH) {
            octet(length)
        } else {
            octet(WspReader.LENGTH_QUOTE)
            uintvar(length.toLong())
        }
    }

    /** Text-string: a quote byte when the text starts with a byte ≥ 128, the text, a NUL. */
    fun text(value: ByteArray) {
        if (value.isNotEmpty() && value[0].toInt() and WspReader.HIGH_BIT != 0) octet(WspReader.QUOTE)
        bytes(value)
        octet(0)
    }

    /** Quoted-string: a `"`, the text, a NUL. */
    fun quoted(value: ByteArray) {
        octet(WspReader.DOUBLE_QUOTE)
        bytes(value)
        octet(0)
    }

    /** Encoded-string-value: plain text when ASCII, else UTF-8 with its character set. */
    fun encodedString(value: String) {
        if (value.all { it.code in ASCII_TEXT }) {
            text(value.toByteArray(Charsets.US_ASCII))
            return
        }
        block {
            shortInteger(MmsCharsets.UTF_8)
            text(value.toByteArray(Charsets.UTF_8))
        }
    }

    /** A value-length prefixed value written by [content]. */
    fun block(content: WspWriter.() -> Unit) {
        val inner = WspWriter().apply(content).toByteArray()
        valueLength(inner.size)
        bytes(inner)
    }

    private companion object {
        val ASCII_TEXT = 0x20..0x7E
    }
}
