package io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms

/**
 * The MMS messages OpenCaller reads and writes, in the binary encoding of OMA MMS Encapsulation (MMS 1.2) over WAP
 * WSP. Android has no public API for this encoding, so the default SMS app does it itself: it reads the download
 * notice from the WAP push and the downloaded message, and writes the message to send and the acknowledgements.
 *
 * Addresses are kept without their `/TYPE=PLMN` suffix. Dates are seconds since 1970 (the MMS encoding).
 */
sealed interface MmsPdu {
    /** The transaction this PDU belongs to, as sent by the MMS centre. */
    val transactionId: String?
}

/** One part of a message body: text, an image, the SMIL layout… */
class MmsPart(
    /** The MIME type, e.g. `text/plain` or `image/jpeg`. */
    val contentType: String,
    /** The bytes, decoded from any transfer encoding. */
    val data: ByteArray,
    /** The IANA MIBenum of a text part's character set, `null` when not given. */
    val charset: Int? = null,
    /** The `name` parameter of the content type. */
    val name: String? = null,
    /** The file name from the content disposition. */
    val filename: String? = null,
    /** The Content-ID, without angle brackets. */
    val contentId: String? = null,
    /** The Content-Location (usually a file name). */
    val contentLocation: String? = null,
) {
    /** True for a text part (`text/plain`). */
    val isText: Boolean get() = contentType.equals(TEXT_PLAIN, ignoreCase = true)

    /** The text of a text part, decoded with its character set. */
    fun text(): String = MmsCharsets.decode(data, charset ?: MmsCharsets.UTF_8)

    /** MIME types the app handles by name. */
    companion object {
        /** Plain text. */
        const val TEXT_PLAIN: String = "text/plain"

        /** The SMIL presentation that lays out the other parts. */
        const val APPLICATION_SMIL: String = "application/smil"
    }
}

/** M-Notification.ind: a message waits at the MMS centre; download it from [contentLocation]. */
data class NotificationInd(
    override val transactionId: String?,
    val from: String?,
    val subject: String?,
    val messageSize: Long,
    /** Seconds since 1970 when the message expires at the centre, `null` when not given. */
    val expiry: Long?,
    val contentLocation: String,
) : MmsPdu

/** M-Retrieve.conf: the downloaded message. */
data class RetrieveConf(
    override val transactionId: String?,
    val messageId: String?,
    val from: String?,
    val to: List<String>,
    val cc: List<String>,
    /** Seconds since 1970 when the message was sent, `null` when not given. */
    val date: Long?,
    val subject: String?,
    val contentType: String,
    val parts: List<MmsPart>,
) : MmsPdu

/** M-Send.conf: the centre's answer to a sent message. */
data class SendConf(
    override val transactionId: String?,
    /** [MmsHeaders.RESPONSE_OK] when the centre accepted the message. */
    val responseStatus: Int,
    val messageId: String?,
) : MmsPdu {
    /** True when the message was accepted. */
    val isAccepted: Boolean get() = responseStatus == MmsHeaders.RESPONSE_OK
}

/** M-Delivery.ind: a delivery report for a sent message. */
data class DeliveryInd(
    val messageId: String?,
    val to: String?,
    /** [MmsHeaders.STATUS_RETRIEVED] when the recipient got the message. */
    val status: Int,
) : MmsPdu {
    override val transactionId: String? get() = null
}

/** M-Send.req: a message to send to [to] (several addresses for a group message). */
class SendReq(
    override val transactionId: String,
    val to: List<String>,
    val subject: String?,
    val parts: List<MmsPart>,
    val deliveryReport: Boolean,
) : MmsPdu

/** M-NotifyResp.ind: tells the centre what happened with a download notice. */
data class NotifyRespInd(
    override val transactionId: String,
    val status: Int,
) : MmsPdu

/** M-Acknowledge.ind: confirms a download. */
data class AcknowledgeInd(
    override val transactionId: String,
) : MmsPdu

/** Header field and value codes of the MMS encapsulation (field codes already have the high bit set). */
object MmsHeaders {
    const val BCC = 0x81
    const val CC = 0x82
    const val CONTENT_LOCATION = 0x83
    const val CONTENT_TYPE = 0x84
    const val DATE = 0x85
    const val DELIVERY_REPORT = 0x86
    const val EXPIRY = 0x88
    const val FROM = 0x89
    const val MESSAGE_CLASS = 0x8A
    const val MESSAGE_ID = 0x8B
    const val MESSAGE_TYPE = 0x8C
    const val MMS_VERSION = 0x8D
    const val MESSAGE_SIZE = 0x8E
    const val READ_REPORT = 0x90
    const val RESPONSE_STATUS = 0x92
    const val STATUS = 0x95
    const val SUBJECT = 0x96
    const val TO = 0x97
    const val TRANSACTION_ID = 0x98

    const val TYPE_SEND_REQ = 0x80
    const val TYPE_SEND_CONF = 0x81
    const val TYPE_NOTIFICATION_IND = 0x82
    const val TYPE_NOTIFYRESP_IND = 0x83
    const val TYPE_RETRIEVE_CONF = 0x84
    const val TYPE_ACKNOWLEDGE_IND = 0x85
    const val TYPE_DELIVERY_IND = 0x86

    const val VALUE_YES = 0x80
    const val VALUE_NO = 0x81
    const val FROM_ADDRESS_PRESENT = 0x80
    const val FROM_INSERT_ADDRESS = 0x81
    const val ABSOLUTE_TOKEN = 0x80
    const val MESSAGE_CLASS_PERSONAL = 0x80

    /** MMS 1.2, the version OpenCaller writes. */
    const val VERSION_1_2 = 0x12

    /** Response-Status: the centre accepted the message. */
    const val RESPONSE_OK: Int = 0x80

    /** Status: the message was retrieved (download notice answered, delivery report). */
    const val STATUS_RETRIEVED: Int = 0x81

    /** Status: the download is deferred (it failed or waits for the user). */
    const val STATUS_DEFERRED: Int = 0x83

    /** Suffix of phone-number addresses in MMS headers. */
    const val PLMN_SUFFIX = "/TYPE=PLMN"
}
