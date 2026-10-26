package io.github.jeshiomurmu.opencaller.core.telephony.messaging

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.model.DeliveryState
import io.github.jeshiomurmu.opencaller.core.model.MessageKey
import io.github.jeshiomurmu.opencaller.core.model.MessageKind
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** An SMS the user wrote, as stored in the outbox (for sending it again). */
data class OutgoingSms(
    val id: Long,
    val address: String,
    val body: String,
    val subscriptionId: Int?,
) {
    /** Never prints the text or the recipient. */
    override fun toString(): String = "OutgoingSms(id=$id, subscriptionId=$subscriptionId)"
}

/**
 * SMS rows in Android's message store (`Telephony.Sms`). As the default SMS app OpenCaller writes every message
 * itself: received ones into the inbox (developer.android.com, `Telephony`: "the default SMS app is responsible for
 * writing any messages to the provider") and its own ones into the outbox, moved to sent or failed by the send
 * result. Nothing here leaves the device.
 */
@Singleton
class DeviceSmsStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val clock: Clock,
        private val dispatchers: DispatcherProvider,
    ) : SmsInbox {
        private val resolver get() = context.contentResolver

        override suspend fun save(sms: IncomingSms): AppResult<StoredMessage> =
            withContext(dispatchers.io) {
                providerCall("inbox write") {
                    val threadId = Telephony.Threads.getOrCreateThreadId(context, sms.address)
                    val values =
                        ContentValues().apply {
                            put(Telephony.Sms.ADDRESS, sms.address)
                            put(Telephony.Sms.BODY, sms.body)
                            put(Telephony.Sms.DATE, clock.nowMillis())
                            put(Telephony.Sms.DATE_SENT, sms.sentAtMillis)
                            put(Telephony.Sms.READ, 0)
                            put(Telephony.Sms.SEEN, 0)
                            put(Telephony.Sms.THREAD_ID, threadId)
                            put(Telephony.Sms.REPLY_PATH_PRESENT, if (sms.replyPathPresent) 1 else 0)
                            sms.subscriptionId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
                            sms.serviceCenter?.let { put(Telephony.Sms.SERVICE_CENTER, it) }
                            sms.protocol?.let { put(Telephony.Sms.PROTOCOL, it) }
                            sms.subject?.let { put(Telephony.Sms.SUBJECT, it) }
                        }
                    val id = resolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values).insertedId("inbox write")
                    StoredMessage(MessageKey(MessageKind.SMS, id), threadId)
                }
            }

        /** Puts the user's message to [address] into the outbox before it is handed to the network. */
        suspend fun addOutgoing(
            address: String,
            body: String,
            subscriptionId: Int?,
            deliveryReport: Boolean,
        ): AppResult<StoredMessage> =
            withContext(dispatchers.io) {
                providerCall("outbox write") {
                    val threadId = Telephony.Threads.getOrCreateThreadId(context, address)
                    val values =
                        ContentValues().apply {
                            put(Telephony.Sms.ADDRESS, address)
                            put(Telephony.Sms.BODY, body)
                            put(Telephony.Sms.DATE, clock.nowMillis())
                            put(Telephony.Sms.READ, 1)
                            put(Telephony.Sms.SEEN, 1)
                            put(Telephony.Sms.THREAD_ID, threadId)
                            put(Telephony.Sms.STATUS, if (deliveryReport) Telephony.Sms.STATUS_PENDING else Telephony.Sms.STATUS_NONE)
                            subscriptionId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
                        }
                    val id = resolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values).insertedId("outbox write")
                    StoredMessage(MessageKey(MessageKind.SMS, id), threadId)
                }
            }

        /** Moves message [id] to the sent box, unless an earlier part already failed it. */
        suspend fun markSent(id: Long): AppResult<Int> =
            update(id, "${Telephony.Sms.TYPE} != ${Telephony.Sms.MESSAGE_TYPE_FAILED}") {
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
            }

        /** Moves message [id] to the failed box with the radio's [errorCode]. */
        suspend fun markFailed(
            id: Long,
            errorCode: Int,
        ): AppResult<Int> =
            update(id, null) {
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
                put(Telephony.Sms.ERROR_CODE, errorCode)
            }

        /** Records what the delivery report said about message [id]. */
        suspend fun markDelivery(
            id: Long,
            state: DeliveryState,
        ): AppResult<Int> =
            update(id, null) {
                put(Telephony.Sms.STATUS, statusOf(state))
                if (state == DeliveryState.FAILED) put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_FAILED)
            }

        /** The user's message [id] from the outbox or failed box, to send it again; `null` when it's gone. */
        suspend fun outgoing(id: Long): AppResult<OutgoingSms?> =
            withContext(dispatchers.io) {
                providerCall("outbox read") {
                    resolver
                        .query(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), OUTGOING_PROJECTION, null, null, null)
                        ?.use { cursor ->
                            if (!cursor.moveToFirst()) return@use null
                            OutgoingSms(
                                id = id,
                                address = cursor.getString(0).orEmpty(),
                                body = cursor.getString(1).orEmpty(),
                                subscriptionId = if (cursor.isNull(2)) null else cursor.getInt(2),
                            )
                        }
                }
            }

        /** Puts failed message [id] back into the outbox for another try. */
        suspend fun requeue(id: Long): AppResult<Int> =
            update(id, null) {
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
                put(Telephony.Sms.DATE, clock.nowMillis())
            }

        private suspend fun update(
            id: Long,
            where: String?,
            values: ContentValues.() -> Unit,
        ): AppResult<Int> =
            withContext(dispatchers.io) {
                providerCall("update") {
                    resolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id), ContentValues().apply(values), where, null)
                }
            }

        private companion object {
            val OUTGOING_PROJECTION = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.SUBSCRIPTION_ID)

            fun statusOf(state: DeliveryState): Int =
                when (state) {
                    DeliveryState.NONE -> Telephony.Sms.STATUS_NONE
                    DeliveryState.PENDING -> Telephony.Sms.STATUS_PENDING
                    DeliveryState.DELIVERED -> Telephony.Sms.STATUS_COMPLETE
                    DeliveryState.FAILED -> Telephony.Sms.STATUS_FAILED
                }
        }
    }
