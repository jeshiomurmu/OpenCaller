package io.github.jeshiomurmu.opencaller.feature.messages.send

import android.content.Context
import android.telephony.SmsManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.DeviceMmsStore
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.StoredMessage
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.MmsHeaders
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.MmsImage
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.MmsMessageBuilder
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.MmsPdu
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.MmsPduComposer
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.NotifyRespInd
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.SendReq
import timber.log.Timber
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends MMS: pictures and group messages (one M-Send.req to every recipient). The message goes into the outbox,
 * its PDU into a [MmsPduFiles] file, and `SmsManager.sendMultimediaMessage` hands it to Android's MMS service, whose
 * result [MessageStatusReceiver] reads (sent with the carrier's message id, or failed).
 */
@Singleton
class MmsSender
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: DeviceMmsStore,
        private val files: MmsPduFiles,
    ) {
        /** The largest MMS the carrier of SIM [subscriptionId] takes (`MMS_CONFIG_MAX_MESSAGE_SIZE`). */
        fun maxMessageBytes(subscriptionId: Int?): Int =
            context
                .smsManagerFor(subscriptionId)
                ?.carrierConfigValues
                ?.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, DEFAULT_MAX_BYTES)
                ?.takeIf { it > 0 } ?: DEFAULT_MAX_BYTES

        /** Sends [text] and [images] to [recipients] (a group message when there are several). */
        suspend fun send(
            recipients: List<String>,
            text: String?,
            images: List<MmsImage>,
            subscriptionId: Int?,
        ): AppResult<StoredMessage> =
            build(recipients, text, images).flatMap { request ->
                store.addOutgoing(request, subscriptionId).flatMap { stored -> dispatch(stored, request, subscriptionId) }
            }

        /** Tells the carrier that the message of [transactionId] was downloaded (M-NotifyResp.ind). */
        fun answerDownload(
            transactionId: String,
            subscriptionId: Int?,
        ) {
            val result =
                hand(
                    NotifyRespInd(transactionId, MmsHeaders.STATUS_RETRIEVED),
                    subscriptionId,
                ) { file -> MessagingIntents.mmsAnswered(context, file) }
            if (result is AppResult.Failure) Timber.w("Download answer not sent: %s", result.error)
        }

        private fun build(
            recipients: List<String>,
            text: String?,
            images: List<MmsImage>,
        ): AppResult<SendReq> =
            try {
                AppResult.Success(MmsMessageBuilder.build(newTransactionId(), recipients.map(String::trim), text, images))
            } catch (e: IllegalArgumentException) {
                AppResult.Failure(AppError.Validation(e.message.orEmpty()))
            }

        private suspend fun dispatch(
            stored: StoredMessage,
            request: SendReq,
            subscriptionId: Int?,
        ): AppResult<StoredMessage> {
            val id = stored.key.id
            val result =
                hand(
                    request,
                    subscriptionId,
                ) { file -> MessagingIntents.mmsSent(context, id, stored.threadId, file, request.deliveryReport) }
            if (result is AppResult.Failure) store.markFailed(id)
            return result.flatMap { AppResult.Success(stored) }
        }

        // Writes [pdu] to a file and hands it to Android's MMS service with the result PendingIntent of [result].
        private fun hand(
            pdu: MmsPdu,
            subscriptionId: Int?,
            result: (file: String) -> android.app.PendingIntent,
        ): AppResult<Unit> {
            val manager = context.smsManagerFor(subscriptionId) ?: return AppResult.Failure(AppError.Validation("no SMS on this device"))
            var file: String? = null
            return try {
                file = files.write(MmsPduComposer.compose(pdu))
                manager.sendMultimediaMessage(context, files.uriOf(file), null, null, result(file))
                AppResult.Success(Unit)
            } catch (e: IOException) {
                Timber.w(e, "MMS file not written")
                file?.let(files::delete)
                AppResult.Failure(AppError.Storage)
            } catch (e: IllegalArgumentException) {
                Timber.w(e, "MMS refused")
                file?.let(files::delete)
                AppResult.Failure(AppError.Validation("refused"))
            }
        }

        private fun newTransactionId(): String =
            "T" +
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(TRANSACTION_ID_LENGTH)

        private companion object {
            const val DEFAULT_MAX_BYTES = 300 * 1024
            const val TRANSACTION_ID_LENGTH = 24
        }
    }
