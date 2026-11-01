package io.github.jeshiomurmu.opencaller.core.model

/** Which table of Android's message store a message lives in. */
enum class MessageKind {
    /** `Telephony.Sms`. */
    SMS,

    /** `Telephony.Mms`. */
    MMS,
}

/** A message's identity in Android's message store: the SMS and MMS tables number their rows separately. */
data class MessageKey(
    val kind: MessageKind,
    val id: Long,
)

/** The box a message is in. */
enum class MessageBox {
    /** Received. */
    INBOX,

    /** Sent by the user and accepted by the network. */
    SENT,

    /** Being sent. */
    OUTBOX,

    /** Sending failed; the user can send it again. */
    FAILED,

    /** Not sent yet. */
    DRAFT,
}

/** What the delivery report says about a sent message. */
enum class DeliveryState {
    /** No report was asked for, or it is a received message. */
    NONE,

    /** Waiting for the report. */
    PENDING,

    /** Delivered to the recipient. */
    DELIVERED,

    /** The network couldn't deliver it. */
    FAILED,
}

/** The download state of a received MMS. */
enum class MmsDownload {
    /** Downloaded (or a sent message). */
    DONE,

    /** Only the notice has arrived; the message is waiting on the carrier's server. */
    AVAILABLE,

    /** Being downloaded. */
    DOWNLOADING,

    /** The download failed; the user can try again. */
    FAILED,
}

/**
 * Someone in a conversation. [address] is the address as Android stores it (a number as received, or an e-mail
 * address); [number] is the parsed number when it is one.
 */
data class Participant(
    val address: String,
    val number: PhoneNumber?,
) {
    /** Never prints the address, so it can't leak into logs. */
    override fun toString(): String = "Participant(number=$number)"
}

/** An MMS part shown in the thread, e.g. an image. [uri] is the part's `content://` address in Android's store. */
data class Attachment(
    val uri: String,
    val contentType: String,
    val name: String? = null,
) {
    /** True for an image the thread can show. */
    val isImage: Boolean get() = contentType.startsWith("image/", ignoreCase = true)
}

/**
 * One message of a thread, read from Android's message store (the text stays there and is never copied into
 * OpenCaller's database, docs/architecture.md §10).
 *
 * @property from the sender of a received message; `null` for the user's own messages.
 * @property subscriptionId the SIM the message was received or sent with, when known.
 */
data class Message(
    val key: MessageKey,
    val threadId: Long,
    val box: MessageBox,
    val from: Participant?,
    val body: String,
    val atMillis: Long,
    val read: Boolean = true,
    val subject: String? = null,
    val delivery: DeliveryState = DeliveryState.NONE,
    val subscriptionId: Int? = null,
    val attachments: List<Attachment> = emptyList(),
    val download: MmsDownload = MmsDownload.DONE,
    /** Bytes of an MMS that is still waiting on the carrier's server. */
    val sizeBytes: Long = 0,
) {
    /** True for the user's own messages. */
    val isOutgoing: Boolean get() = box != MessageBox.INBOX

    /** Never prints the text or the sender. */
    override fun toString(): String = "Message(key=$key, threadId=$threadId, box=$box, delivery=$delivery, download=$download)"
}

/**
 * A conversation of the messages list: the other people in it (several for a group MMS), its latest message and how
 * many messages are unread.
 */
data class Conversation(
    val threadId: Long,
    val participants: List<Participant>,
    val snippet: String,
    val lastAtMillis: Long,
    val unreadCount: Int = 0,
    val lastFromMe: Boolean = false,
    val lastHasImage: Boolean = false,
) {
    /** True for a group conversation. */
    val isGroup: Boolean get() = participants.size > 1

    /** Never prints the snippet or the people. */
    override fun toString(): String = "Conversation(threadId=$threadId, participants=${participants.size}, unread=$unreadCount)"
}

/** A SIM that can send messages. [label] is the name Android gives it (empty when it has none). */
data class MessagingSim(
    val subscriptionId: Int,
    val label: String,
    val slotIndex: Int,
)

/** The SIMs for sending messages and the one Android uses by default (`null` when it asks every time). */
data class MessagingSims(
    val options: List<MessagingSim> = emptyList(),
    val defaultId: Int? = null,
) {
    /** The SIM a message goes out with when the user hasn't picked one. */
    val preferred: MessagingSim? get() = options.firstOrNull { it.subscriptionId == defaultId } ?: options.singleOrNull()

    /** True when the composer has to ask: more than one SIM and no default. */
    val needsChoice: Boolean get() = options.size > 1 && preferred == null
}

/**
 * What OpenCaller keeps about a received SMS in its own database: the category and the sender's name, never the
 * text (docs/architecture.md §4.4 step 7). [providerId] is the row in `Telephony.Sms`.
 */
data class MessageMeta(
    val providerId: Long,
    val threadId: Long,
    val category: MessageCategory,
    val senderName: String?,
    val senderSource: NameSource?,
    val atMillis: Long,
    val otp: OtpInfo? = null,
    val riskFlags: Int = 0,
    val classifierScore: Float = 0f,
) {
    /** Never prints the sender's name or the code. */
    override fun toString(): String = "MessageMeta(providerId=$providerId, threadId=$threadId, category=$category)"
}
