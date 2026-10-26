package io.github.jeshiomurmu.opencaller.core.telephony.messaging

import android.content.ContentResolver
import android.database.Cursor
import android.provider.Telephony
import io.github.jeshiomurmu.opencaller.core.model.Attachment
import io.github.jeshiomurmu.opencaller.core.model.Conversation
import io.github.jeshiomurmu.opencaller.core.model.DeliveryState
import io.github.jeshiomurmu.opencaller.core.model.Message
import io.github.jeshiomurmu.opencaller.core.model.MessageBox
import io.github.jeshiomurmu.opencaller.core.model.MessageKey
import io.github.jeshiomurmu.opencaller.core.model.MessageKind
import io.github.jeshiomurmu.opencaller.core.model.MmsDownload
import io.github.jeshiomurmu.opencaller.core.model.Participant
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.MmsHeaders
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.MmsPart
import java.util.concurrent.TimeUnit

/** Turns an address into a [Participant] (parses numbers in the user's region). */
fun interface ParticipantFactory {
    /** The participant for [address]. */
    fun of(address: String): Participant
}

/**
 * Reads conversations and threads from the documented `Telephony.Sms` and `Telephony.Mms` tables (their parts and
 * addresses), never from the undocumented thread tables. Runs on the caller's thread; [DeviceConversations] calls it
 * on the I/O dispatcher.
 */
@Suppress("TooManyFunctions") // The queries and the row mapping of one reader over both tables.
internal class ConversationReader(
    private val resolver: ContentResolver,
    private val participants: ParticipantFactory,
) {
    /** The newest message of each conversation, newest conversation first. */
    fun conversations(self: Collection<String>): List<Conversation> {
        val latest = HashMap<Long, Latest>()
        val unread = HashMap<Long, Int>()
        smsRows(null, null, CONVERSATION_SCAN) { row ->
            latest.merge(row.threadId, Latest(row.atMillis, row.body, row.isOutgoing, false, smsAddress = row.address), ::newer)
            if (!row.read) unread.merge(row.threadId, 1, Int::plus)
        }
        mmsRows(null, null, CONVERSATION_SCAN) { row ->
            latest.merge(row.threadId, Latest(row.atMillis, row.subject.orEmpty(), row.isOutgoing, true, mmsId = row.id), ::newer)
            if (!row.read) unread.merge(row.threadId, 1, Int::plus)
        }
        return latest.entries
            .sortedByDescending { it.value.atMillis }
            .map { (threadId, last) -> conversationOf(threadId, last, unread[threadId] ?: 0, self) }
    }

    /** The newest messages of thread [threadId] (up to [THREAD_LIMIT]), oldest first. */
    fun thread(
        threadId: Long,
        self: Collection<String>,
    ): List<Message> {
        val where = "${Telephony.Sms.THREAD_ID} = ?"
        val args = arrayOf(threadId.toString())
        val messages = mutableListOf<Message>()
        smsRows(where, args, THREAD_LIMIT) { messages += it.toMessage() }
        val mms = mutableListOf<MmsRow>()
        mmsRows(where, args, THREAD_LIMIT) { mms += it }
        val parts = parts(mms.map(MmsRow::id))
        val group = mms.isNotEmpty() && others(mms.first().id, self).size > 1
        mms.forEach { row ->
            val from = if (group && !row.isOutgoing) sender(row.id) else null
            messages += row.toMessage(parts[row.id].orEmpty(), from)
        }
        return messages.sortedBy(Message::atMillis).takeLast(THREAD_LIMIT)
    }

    /** The other people of thread [threadId]: from its newest MMS when there is one, else its SMS. */
    fun participantsOf(
        threadId: Long,
        self: Collection<String>,
    ): List<Participant> {
        val args = arrayOf(threadId.toString())
        var mmsId: Long? = null
        mmsRows("${Telephony.Mms.THREAD_ID} = ?", args, 1) { mmsId = it.id }
        val addresses = mmsId?.let { others(it, self) } ?: smsAddresses(args)
        return addresses.map(participants::of)
    }

    private fun conversationOf(
        threadId: Long,
        last: Latest,
        unread: Int,
        self: Collection<String>,
    ): Conversation {
        val mmsParts = last.mmsId?.let { parts(listOf(it))[it] }.orEmpty()
        val snippet = if (last.isMms) mmsParts.firstOrNull { it.text != null }?.text ?: last.snippet else last.snippet
        val addresses = last.mmsId?.let { others(it, self) } ?: listOfNotNull(last.smsAddress)
        return Conversation(
            threadId = threadId,
            participants = MessageAddresses.distinct(addresses).map(participants::of),
            snippet = snippet,
            lastAtMillis = last.atMillis,
            unreadCount = unread,
            lastFromMe = last.fromMe,
            lastHasImage = mmsParts.any { it.attachment?.isImage == true },
        )
    }

    private fun newer(
        a: Latest,
        b: Latest,
    ): Latest = if (b.atMillis > a.atMillis) b else a

    private fun smsAddresses(args: Array<String>): List<String> {
        val addresses = mutableListOf<String>()
        smsRows("${Telephony.Sms.THREAD_ID} = ?", args, ADDRESS_SCAN) { it.address?.let(addresses::add) }
        return MessageAddresses.distinct(addresses)
    }

    // The sender and recipients of MMS [id] other than the user (the From placeholder of sent ones is dropped).
    private fun others(
        id: Long,
        self: Collection<String>,
    ): List<String> {
        val rows = addressRows(id)
        val from = rows.firstOrNull { it.second == MmsHeaders.FROM }?.first
        val to = rows.filter { it.second == MmsHeaders.TO }.map { it.first }
        val cc = rows.filter { it.second == MmsHeaders.CC }.map { it.first }
        val placeholder = from == null || from.equals(MessageAddresses.INSERT_ADDRESS_TOKEN, ignoreCase = true)
        val sent = placeholder || self.any { MessageAddresses.same(it, from.orEmpty()) }
        return if (sent) MessageAddresses.distinct(to + cc) else MessageAddresses.othersInReceived(from, to, cc, self)
    }

    private fun sender(id: Long): Participant? =
        addressRows(id)
            .firstOrNull { it.second == MmsHeaders.FROM }
            ?.first
            ?.takeUnless { it.equals(MessageAddresses.INSERT_ADDRESS_TOKEN, true) }
            ?.let(participants::of)

    private fun addressRows(id: Long): List<Pair<String, Int>> =
        resolver
            .query(MmsUris.addresses(id), ADDRESS_PROJECTION, null, null, null)
            ?.use { cursor ->
                buildList { while (cursor.moveToNext()) cursor.getString(0)?.let { add(it to cursor.getInt(1)) } }
            }.orEmpty()

    // The parts of MMS [ids], by message: text, and images and other media as attachments (SMIL is left out).
    private fun parts(ids: List<Long>): Map<Long, List<PartRow>> {
        if (ids.isEmpty()) return emptyMap()
        return ids.chunked(MAX_ARGS).flatMap(::partRows).groupBy({ it.first }, { it.second })
    }

    // (message id, part) of the parts of the messages in [chunk].
    private fun partRows(chunk: List<Long>): List<Pair<Long, PartRow>> {
        val where = "${Telephony.Mms.Part.MSG_ID} IN (${chunk.joinToString(",") { "?" }})"
        val args = chunk.map(Long::toString).toTypedArray()
        return resolver
            .query(Telephony.Mms.Part.CONTENT_URI, PART_PROJECTION, where, args, null)
            ?.use { cursor -> buildList { while (cursor.moveToNext()) partOf(cursor)?.let { add(cursor.getLong(1) to it) } } }
            .orEmpty()
    }

    private fun partOf(cursor: Cursor): PartRow? {
        val type = cursor.getString(PART_TYPE) ?: return null
        return when {
            type.equals(MmsPart.APPLICATION_SMIL, ignoreCase = true) -> null
            type.equals(MmsPart.TEXT_PLAIN, ignoreCase = true) -> PartRow(text = cursor.getString(PART_TEXT).orEmpty())
            else -> PartRow(attachment = Attachment(MmsUris.part(cursor.getLong(0)).toString(), type, name = cursor.getString(PART_NAME)))
        }
    }

    private inline fun smsRows(
        where: String?,
        args: Array<String>?,
        limit: Int,
        each: (SmsRow) -> Unit,
    ) {
        resolver.query(Telephony.Sms.CONTENT_URI, SMS_PROJECTION, where, args, "${Telephony.Sms.DATE} DESC")?.use { cursor ->
            var seen = 0
            while (seen++ < limit && cursor.moveToNext()) each(smsRowOf(cursor))
        }
    }

    private inline fun mmsRows(
        where: String?,
        args: Array<String>?,
        limit: Int,
        each: (MmsRow) -> Unit,
    ) {
        val shown = listOf(MmsHeaders.TYPE_SEND_REQ, MmsHeaders.TYPE_NOTIFICATION_IND, MmsHeaders.TYPE_RETRIEVE_CONF)
        val types = "${Telephony.Mms.MESSAGE_TYPE} IN (${shown.joinToString()})"
        val selection = if (where == null) types else "$where AND $types"
        resolver.query(Telephony.Mms.CONTENT_URI, MMS_PROJECTION, selection, args, "${Telephony.Mms.DATE} DESC")?.use { cursor ->
            var seen = 0
            while (seen++ < limit && cursor.moveToNext()) each(mmsRowOf(cursor))
        }
    }

    private fun smsRowOf(cursor: Cursor) =
        SmsRow(
            id = cursor.getLong(0),
            threadId = cursor.getLong(1),
            address = cursor.getString(SMS_ADDRESS),
            body = cursor.getString(SMS_BODY).orEmpty(),
            atMillis = cursor.getLong(SMS_DATE),
            read = cursor.getInt(SMS_READ) != 0,
            type = cursor.getInt(SMS_TYPE),
            status = if (cursor.isNull(SMS_STATUS)) Telephony.Sms.STATUS_NONE else cursor.getInt(SMS_STATUS),
            subscriptionId = if (cursor.isNull(SMS_SUBSCRIPTION)) null else cursor.getInt(SMS_SUBSCRIPTION),
        )

    private fun mmsRowOf(cursor: Cursor) =
        MmsRow(
            id = cursor.getLong(0),
            threadId = cursor.getLong(1),
            atMillis = TimeUnit.SECONDS.toMillis(cursor.getLong(MMS_DATE)),
            read = cursor.getInt(MMS_READ) != 0,
            box = cursor.getInt(MMS_BOX),
            type = cursor.getInt(MMS_TYPE),
            subject = cursor.getString(MMS_SUBJECT),
            status = if (cursor.isNull(MMS_STATUS)) 0 else cursor.getInt(MMS_STATUS),
            sizeBytes = cursor.getLong(MMS_SIZE),
            subscriptionId = if (cursor.isNull(MMS_SUBSCRIPTION)) null else cursor.getInt(MMS_SUBSCRIPTION),
        )

    private fun SmsRow.toMessage(): Message =
        Message(
            key = MessageKey(MessageKind.SMS, id),
            threadId = threadId,
            box = smsBoxOf(type),
            from = if (isOutgoing) null else address?.let(participants::of),
            body = body,
            atMillis = atMillis,
            read = read,
            delivery = smsDeliveryOf(status, type),
            subscriptionId = subscriptionId,
        )

    private fun MmsRow.toMessage(
        parts: List<PartRow>,
        sender: Participant?,
    ): Message {
        val notice = type == MmsHeaders.TYPE_NOTIFICATION_IND
        return Message(
            key = MessageKey(MessageKind.MMS, id),
            threadId = threadId,
            box = mmsBoxOf(box),
            from = sender,
            body = parts.mapNotNull(PartRow::text).joinToString("\n"),
            atMillis = atMillis,
            read = read,
            subject = subject?.takeIf(String::isNotBlank),
            delivery = if (box == Telephony.Mms.MESSAGE_BOX_SENT) DeviceMmsStore.deliveryOf(status) else DeliveryState.NONE,
            subscriptionId = subscriptionId,
            attachments = parts.mapNotNull(PartRow::attachment),
            download = if (notice) DeviceMmsStore.downloadOf(status) else MmsDownload.DONE,
            sizeBytes = if (notice) sizeBytes else 0,
        )
    }

    private data class Latest(
        val atMillis: Long,
        val snippet: String,
        val fromMe: Boolean,
        val isMms: Boolean,
        val smsAddress: String? = null,
        val mmsId: Long? = null,
    )

    private class PartRow(
        val text: String? = null,
        val attachment: Attachment? = null,
    )

    private data class SmsRow(
        val id: Long,
        val threadId: Long,
        val address: String?,
        val body: String,
        val atMillis: Long,
        val read: Boolean,
        val type: Int,
        val status: Int,
        val subscriptionId: Int?,
    ) {
        val isOutgoing: Boolean get() = type != Telephony.Sms.MESSAGE_TYPE_INBOX
    }

    private data class MmsRow(
        val id: Long,
        val threadId: Long,
        val atMillis: Long,
        val read: Boolean,
        val box: Int,
        val type: Int,
        val subject: String?,
        val status: Int,
        val sizeBytes: Long,
        val subscriptionId: Int?,
    ) {
        val isOutgoing: Boolean get() = box != Telephony.Mms.MESSAGE_BOX_INBOX
    }

    /** How much is read. */
    companion object {
        /** Messages of each table scanned for the conversations list. */
        const val CONVERSATION_SCAN = 5_000

        /** Newest messages shown in a thread. */
        const val THREAD_LIMIT = 500

        private const val ADDRESS_SCAN = 50

        // Column positions in the projections below.
        private const val PART_TYPE = 2
        private const val PART_NAME = 3
        private const val PART_TEXT = 4
        private const val SMS_ADDRESS = 2
        private const val SMS_BODY = 3
        private const val SMS_DATE = 4
        private const val SMS_READ = 5
        private const val SMS_TYPE = 6
        private const val SMS_STATUS = 7
        private const val SMS_SUBSCRIPTION = 8
        private const val MMS_DATE = 2
        private const val MMS_READ = 3
        private const val MMS_BOX = 4
        private const val MMS_TYPE = 5
        private const val MMS_SUBJECT = 6
        private const val MMS_STATUS = 7
        private const val MMS_SIZE = 8
        private const val MMS_SUBSCRIPTION = 9
        private const val MAX_ARGS = 500

        private val SMS_PROJECTION =
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.READ,
                Telephony.Sms.TYPE,
                Telephony.Sms.STATUS,
                Telephony.Sms.SUBSCRIPTION_ID,
            )
        private val MMS_PROJECTION =
            arrayOf(
                Telephony.Mms._ID,
                Telephony.Mms.THREAD_ID,
                Telephony.Mms.DATE,
                Telephony.Mms.READ,
                Telephony.Mms.MESSAGE_BOX,
                Telephony.Mms.MESSAGE_TYPE,
                Telephony.Mms.SUBJECT,
                Telephony.Mms.STATUS,
                Telephony.Mms.MESSAGE_SIZE,
                Telephony.Mms.SUBSCRIPTION_ID,
            )
        private val PART_PROJECTION =
            arrayOf(
                Telephony.Mms.Part._ID,
                Telephony.Mms.Part.MSG_ID,
                Telephony.Mms.Part.CONTENT_TYPE,
                Telephony.Mms.Part.NAME,
                Telephony.Mms.Part.TEXT,
            )
        private val ADDRESS_PROJECTION = arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE)

        /** The box of an SMS `type`. */
        internal fun smsBoxOf(type: Int): MessageBox =
            when (type) {
                Telephony.Sms.MESSAGE_TYPE_INBOX -> MessageBox.INBOX
                Telephony.Sms.MESSAGE_TYPE_SENT -> MessageBox.SENT
                Telephony.Sms.MESSAGE_TYPE_FAILED -> MessageBox.FAILED
                Telephony.Sms.MESSAGE_TYPE_DRAFT -> MessageBox.DRAFT
                else -> MessageBox.OUTBOX
            }

        /** The box of an MMS `msg_box`. */
        internal fun mmsBoxOf(box: Int): MessageBox =
            when (box) {
                Telephony.Mms.MESSAGE_BOX_INBOX -> MessageBox.INBOX
                Telephony.Mms.MESSAGE_BOX_SENT -> MessageBox.SENT
                Telephony.Mms.MESSAGE_BOX_FAILED -> MessageBox.FAILED
                Telephony.Mms.MESSAGE_BOX_DRAFTS -> MessageBox.DRAFT
                else -> MessageBox.OUTBOX
            }

        /** The delivery state of an SMS `status` (only sent messages have one). */
        internal fun smsDeliveryOf(
            status: Int,
            type: Int,
        ): DeliveryState =
            when {
                type == Telephony.Sms.MESSAGE_TYPE_INBOX -> DeliveryState.NONE
                status == Telephony.Sms.STATUS_COMPLETE -> DeliveryState.DELIVERED
                status == Telephony.Sms.STATUS_FAILED -> DeliveryState.FAILED
                status == Telephony.Sms.STATUS_PENDING -> DeliveryState.PENDING
                else -> DeliveryState.NONE
            }
    }
}
