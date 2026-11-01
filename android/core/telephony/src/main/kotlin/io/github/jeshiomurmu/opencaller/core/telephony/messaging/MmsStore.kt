package io.github.jeshiomurmu.opencaller.core.telephony.messaging

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.model.DeliveryState
import io.github.jeshiomurmu.opencaller.core.model.MessageKey
import io.github.jeshiomurmu.opencaller.core.model.MessageKind
import io.github.jeshiomurmu.opencaller.core.model.MmsDownload
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.MmsCharsets
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.MmsHeaders
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.MmsPart
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.NotificationInd
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.RetrieveConf
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.SendReq
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A download notice waiting on the carrier's server. */
data class PendingDownload(
    val id: Long,
    val threadId: Long,
    val contentLocation: String,
    val transactionId: String?,
    val subscriptionId: Int?,
)

/**
 * MMS rows in Android's message store (`Telephony.Mms`, its parts and addresses). The default SMS app persists MMS
 * itself: a download notice as soon as it arrives, the downloaded message in its place, and the user's own messages
 * in the outbox until the carrier accepts them. The `st` column holds OpenCaller's download state on notice rows
 * (as AOSP Messaging does) and the delivery report on sent rows.
 */
@Suppress("TooManyFunctions") // One function per store operation, plus the row writers they share.
@Singleton
class DeviceMmsStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val clock: Clock,
        private val dispatchers: DispatcherProvider,
    ) {
        private val resolver: ContentResolver get() = context.contentResolver

        /** Stores [notice] (once: carriers repeat notices) and returns its row. */
        suspend fun saveNotice(
            notice: NotificationInd,
            subscriptionId: Int?,
        ): AppResult<StoredMessage> =
            io("notice write") {
                existingNotice(notice.contentLocation)?.let { return@io it }
                val from = notice.from?.takeIf(String::isNotBlank) ?: throw StoreRefusedException("notice without a sender")
                val threadId = Telephony.Threads.getOrCreateThreadId(context, from)
                val values =
                    ContentValues().apply {
                        common(threadId, MmsHeaders.TYPE_NOTIFICATION_IND, Telephony.Mms.MESSAGE_BOX_INBOX, subscriptionId, read = false)
                        put(Telephony.Mms.CONTENT_LOCATION, notice.contentLocation)
                        put(Telephony.Mms.TRANSACTION_ID, notice.transactionId)
                        put(Telephony.Mms.MESSAGE_SIZE, notice.messageSize)
                        notice.expiry?.let { put(Telephony.Mms.EXPIRY, it) }
                        subject(notice.subject)
                        put(Telephony.Mms.STATUS, DOWNLOAD_AVAILABLE)
                    }
                val id = resolver.insert(Telephony.Mms.Inbox.CONTENT_URI, values).insertedId("notice write")
                addAddresses(id, listOf(from to TYPE_FROM))
                StoredMessage(MessageKey(MessageKind.MMS, id), threadId)
            }

        /** The notice [id], to download it; `null` when it is gone or already downloaded. */
        suspend fun pendingDownload(id: Long): AppResult<PendingDownload?> =
            io("notice read") {
                val selection = "${Telephony.Mms.MESSAGE_TYPE} = ${MmsHeaders.TYPE_NOTIFICATION_IND}"
                resolver.query(rowUri(id), NOTICE_PROJECTION, selection, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    PendingDownload(
                        id = id,
                        threadId = cursor.getLong(0),
                        contentLocation = cursor.getString(1) ?: return@use null,
                        transactionId = cursor.getString(NOTICE_TRANSACTION),
                        subscriptionId = if (cursor.isNull(NOTICE_SUBSCRIPTION)) null else cursor.getInt(NOTICE_SUBSCRIPTION),
                    )
                }
            }

        /** Records the download state of notice [id]. */
        suspend fun setDownload(
            id: Long,
            state: MmsDownload,
        ): AppResult<Int> = update(id) { put(Telephony.Mms.STATUS, downloadStatusOf(state)) }

        /**
         * Replaces notice [noticeId] with the downloaded [message] in the conversation of its sender and the other
         * recipients ([self] are the user's own numbers, left out of the group).
         */
        suspend fun saveRetrieved(
            noticeId: Long,
            message: RetrieveConf,
            subscriptionId: Int?,
            self: Collection<String>,
        ): AppResult<StoredMessage> =
            io("message write") {
                val others = MessageAddresses.othersInReceived(message.from, message.to, message.cc, self)
                if (others.isEmpty()) throw StoreRefusedException("message without addresses")
                val threadId = Telephony.Threads.getOrCreateThreadId(context, others.toSet())
                val values =
                    ContentValues().apply {
                        common(threadId, MmsHeaders.TYPE_RETRIEVE_CONF, Telephony.Mms.MESSAGE_BOX_INBOX, subscriptionId, read = false)
                        message.date?.let { put(Telephony.Mms.DATE_SENT, it) }
                        put(Telephony.Mms.MESSAGE_ID, message.messageId)
                        put(Telephony.Mms.TRANSACTION_ID, message.transactionId)
                        put(Telephony.Mms.CONTENT_TYPE, message.contentType)
                        subject(message.subject)
                    }
                val id = resolver.insert(Telephony.Mms.Inbox.CONTENT_URI, values).insertedId("message write")
                addParts(id, message.parts)
                val addresses =
                    listOfNotNull(message.from?.let { it to TYPE_FROM }) + message.to.map { it to TYPE_TO } +
                        message.cc.map { it to TYPE_CC }
                addAddresses(id, addresses)
                resolver.delete(rowUri(noticeId), null, null)
                StoredMessage(MessageKey(MessageKind.MMS, id), threadId)
            }

        /** Puts the user's [message] into the outbox before it is handed to the carrier. */
        suspend fun addOutgoing(
            message: SendReq,
            subscriptionId: Int?,
        ): AppResult<StoredMessage> =
            io("outbox write") {
                val threadId = Telephony.Threads.getOrCreateThreadId(context, message.to.toSet())
                val values =
                    ContentValues().apply {
                        common(threadId, MmsHeaders.TYPE_SEND_REQ, Telephony.Mms.MESSAGE_BOX_OUTBOX, subscriptionId, read = true)
                        put(Telephony.Mms.TRANSACTION_ID, message.transactionId)
                        put(Telephony.Mms.CONTENT_TYPE, MULTIPART_RELATED)
                        put(Telephony.Mms.DELIVERY_REPORT, if (message.deliveryReport) MmsHeaders.VALUE_YES else MmsHeaders.VALUE_NO)
                        put(Telephony.Mms.MESSAGE_CLASS, CLASS_PERSONAL)
                        subject(message.subject)
                    }
                val id = resolver.insert(Telephony.Mms.Outbox.CONTENT_URI, values).insertedId("outbox write")
                addParts(id, message.parts)
                addAddresses(id, listOf(MessageAddresses.INSERT_ADDRESS_TOKEN to TYPE_FROM) + message.to.map { it to TYPE_TO })
                StoredMessage(MessageKey(MessageKind.MMS, id), threadId)
            }

        /** Moves message [id] to the sent box with the carrier's [messageId] (for its delivery report). */
        suspend fun markSent(
            id: Long,
            messageId: String?,
            deliveryReport: Boolean,
        ): AppResult<Int> =
            update(id) {
                put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_SENT)
                messageId?.let { put(Telephony.Mms.MESSAGE_ID, it) }
                put(Telephony.Mms.STATUS, if (deliveryReport) DELIVERY_PENDING else DELIVERY_NONE)
            }

        /** Moves message [id] to the failed box. */
        suspend fun markFailed(id: Long): AppResult<Int> = update(id) { put(Telephony.Mms.MESSAGE_BOX, Telephony.Mms.MESSAGE_BOX_FAILED) }

        /** Records the delivery report for the sent message with the carrier's [messageId]. */
        suspend fun markDelivery(
            messageId: String,
            state: DeliveryState,
        ): AppResult<Int> =
            io("delivery update") {
                val values = ContentValues().apply { put(Telephony.Mms.STATUS, deliveryStatusOf(state)) }
                val where = "${Telephony.Mms.MESSAGE_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_SENT}"
                resolver.update(Telephony.Mms.CONTENT_URI, values, where, arrayOf(messageId))
            }

        private fun existingNotice(location: String): StoredMessage? {
            val where = "${Telephony.Mms.CONTENT_LOCATION} = ? AND ${Telephony.Mms.MESSAGE_TYPE} = ${MmsHeaders.TYPE_NOTIFICATION_IND}"
            return resolver.query(Telephony.Mms.CONTENT_URI, ID_THREAD, where, arrayOf(location), null)?.use { cursor ->
                if (cursor.moveToFirst()) StoredMessage(MessageKey(MessageKind.MMS, cursor.getLong(0)), cursor.getLong(1)) else null
            }
        }

        private fun ContentValues.common(
            threadId: Long,
            type: Int,
            box: Int,
            subscriptionId: Int?,
            read: Boolean,
        ) {
            put(Telephony.Mms.THREAD_ID, threadId)
            put(Telephony.Mms.DATE, TimeUnit.MILLISECONDS.toSeconds(clock.nowMillis()))
            put(Telephony.Mms.MESSAGE_TYPE, type)
            put(Telephony.Mms.MESSAGE_BOX, box)
            put(Telephony.Mms.MMS_VERSION, MmsHeaders.VERSION_1_2)
            put(Telephony.Mms.READ, if (read) 1 else 0)
            put(Telephony.Mms.SEEN, if (read) 1 else 0)
            subscriptionId?.let { put(Telephony.Mms.SUBSCRIPTION_ID, it) }
        }

        private fun ContentValues.subject(subject: String?) {
            if (subject.isNullOrEmpty()) return
            put(Telephony.Mms.SUBJECT, subject)
            put(Telephony.Mms.SUBJECT_CHARSET, MmsCharsets.UTF_8)
        }

        // Text parts go into the text column; others are written as files through the provider.
        private fun addParts(
            messageId: Long,
            parts: List<MmsPart>,
        ) {
            val uri = MmsUris.parts(messageId)
            parts.forEach { part ->
                val textual = part.isText || part.contentType.equals(MmsPart.APPLICATION_SMIL, ignoreCase = true)
                val values =
                    ContentValues().apply {
                        put(Telephony.Mms.Part.MSG_ID, messageId)
                        put(Telephony.Mms.Part.CONTENT_TYPE, part.contentType)
                        part.contentId?.let { put(Telephony.Mms.Part.CONTENT_ID, "<$it>") }
                        part.contentLocation?.let { put(Telephony.Mms.Part.CONTENT_LOCATION, it) }
                        (part.name ?: part.filename)?.let { put(Telephony.Mms.Part.NAME, it) }
                        part.filename?.let { put(Telephony.Mms.Part.FILENAME, it) }
                        if (textual) {
                            put(Telephony.Mms.Part.CHARSET, MmsCharsets.UTF_8)
                            put(Telephony.Mms.Part.TEXT, part.text())
                        }
                    }
                val partUri = resolver.insert(uri, values) ?: throw StoreRefusedException("part write")
                if (!textual) {
                    resolver.openOutputStream(partUri)?.use { it.write(part.data) } ?: throw StoreRefusedException("part data")
                }
            }
        }

        private fun addAddresses(
            messageId: Long,
            addresses: List<Pair<String, Int>>,
        ) {
            val uri = MmsUris.addresses(messageId)
            addresses.forEach { (address, type) ->
                val values =
                    ContentValues().apply {
                        put(Telephony.Mms.Addr.MSG_ID, messageId)
                        put(Telephony.Mms.Addr.ADDRESS, address)
                        put(Telephony.Mms.Addr.TYPE, type)
                        put(Telephony.Mms.Addr.CHARSET, MmsCharsets.UTF_8)
                    }
                resolver.insert(uri, values) ?: throw StoreRefusedException("address write")
            }
        }

        private suspend fun update(
            id: Long,
            values: ContentValues.() -> Unit,
        ): AppResult<Int> = io("update") { resolver.update(rowUri(id), ContentValues().apply(values), null, null) }

        private suspend fun <T> io(
            what: String,
            block: () -> T,
        ): AppResult<T> = withContext(dispatchers.io) { providerCall(what, block) }

        private fun rowUri(id: Long): Uri = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, id)

        /** Values of the `st` column. */
        companion object {
            /** Notice row: waiting to be downloaded. */
            const val DOWNLOAD_AVAILABLE = 0x80

            /** Notice row: being downloaded. */
            const val DOWNLOADING = 0x81

            /** Notice row: the download failed. */
            const val DOWNLOAD_FAILED = 0x87

            /** Sent row: no delivery report asked for. */
            const val DELIVERY_NONE = 0

            /** Sent row: waiting for the delivery report. */
            const val DELIVERY_PENDING = 0x20

            /** Sent row: delivered (the MMS Status value "Retrieved"). */
            const val DELIVERY_DONE = MmsHeaders.STATUS_RETRIEVED

            /** Sent row: not delivered. */
            const val DELIVERY_FAILED = 0x40

            /** Address types (MMS header field codes: From, To, Cc). */
            const val TYPE_FROM = MmsHeaders.FROM
            const val TYPE_TO = MmsHeaders.TO
            const val TYPE_CC = MmsHeaders.CC

            private const val NOTICE_TRANSACTION = 2
            private const val NOTICE_SUBSCRIPTION = 3
            private const val MULTIPART_RELATED = "application/vnd.wap.multipart.related"
            private const val CLASS_PERSONAL = "personal"
            private val ID_THREAD = arrayOf(Telephony.Mms._ID, Telephony.Mms.THREAD_ID)
            private val NOTICE_PROJECTION =
                arrayOf(
                    Telephony.Mms.THREAD_ID,
                    Telephony.Mms.CONTENT_LOCATION,
                    Telephony.Mms.TRANSACTION_ID,
                    Telephony.Mms.SUBSCRIPTION_ID,
                )

            /** The download state stored for [state]. */
            fun downloadStatusOf(state: MmsDownload): Int =
                when (state) {
                    MmsDownload.DOWNLOADING -> DOWNLOADING
                    MmsDownload.FAILED -> DOWNLOAD_FAILED
                    MmsDownload.AVAILABLE, MmsDownload.DONE -> DOWNLOAD_AVAILABLE
                }

            /** The download state of a notice row's `st` value. */
            fun downloadOf(status: Int): MmsDownload =
                when (status) {
                    DOWNLOADING -> MmsDownload.DOWNLOADING
                    DOWNLOAD_FAILED -> MmsDownload.FAILED
                    else -> MmsDownload.AVAILABLE
                }

            /** The `st` value stored for a delivery [state]. */
            fun deliveryStatusOf(state: DeliveryState): Int =
                when (state) {
                    DeliveryState.NONE -> DELIVERY_NONE
                    DeliveryState.PENDING -> DELIVERY_PENDING
                    DeliveryState.DELIVERED -> DELIVERY_DONE
                    DeliveryState.FAILED -> DELIVERY_FAILED
                }

            /** The delivery state of a sent row's `st` value. */
            fun deliveryOf(status: Int): DeliveryState =
                when (status) {
                    DELIVERY_PENDING -> DeliveryState.PENDING
                    DELIVERY_DONE -> DeliveryState.DELIVERED
                    DELIVERY_FAILED -> DeliveryState.FAILED
                    else -> DeliveryState.NONE
                }
        }
    }
