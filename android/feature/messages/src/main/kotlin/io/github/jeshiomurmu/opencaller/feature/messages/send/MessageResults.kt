package io.github.jeshiomurmu.opencaller.feature.messages.send

import android.app.Activity
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SmsMessage
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.DeliveryReports
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.DeviceMmsStore
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.DeviceSmsStore
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.MalformedPduException
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.MmsPduParser
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.SendConf
import io.github.jeshiomurmu.opencaller.feature.messages.notify.MessageNotifier
import timber.log.Timber
import javax.inject.Inject

/**
 * What the results of sends, delivery reports and downloads change: the message's box and status in Android's store,
 * a "not sent" notification, a downloaded MMS in its conversation. Called by [MessageStatusReceiver] with the
 * broadcast's result code (`Activity.RESULT_OK` on success).
 */
class MessageResults
    @Inject
    constructor(
        private val sms: DeviceSmsStore,
        private val mms: DeviceMmsStore,
        private val downloader: MmsDownloader,
        private val files: MmsPduFiles,
        private val notifier: MessageNotifier,
    ) {
        /** Handles [intent] with [resultCode]. */
        suspend fun handle(
            intent: Intent,
            resultCode: Int,
        ) {
            val ok = resultCode == Activity.RESULT_OK
            val id = intent.getLongExtra(MessagingIntents.EXTRA_ID, NONE)
            if (id == NONE && intent.action != MessagingIntents.ACTION_MMS_ANSWERED) return
            when (intent.action) {
                MessagingIntents.ACTION_SMS_SENT -> smsSent(intent, id, ok, resultCode)
                MessagingIntents.ACTION_SMS_DELIVERED -> smsDelivered(intent, id)
                MessagingIntents.ACTION_MMS_SENT -> mmsSent(intent, id, ok)
                MessagingIntents.ACTION_MMS_DOWNLOADED -> mmsDownloaded(intent, id, ok)
                MessagingIntents.ACTION_MMS_ANSWERED -> intent.getStringExtra(MessagingIntents.EXTRA_FILE)?.let(files::delete)
            }
        }

        // A failed part fails the message; the last part's success makes it sent.
        private suspend fun smsSent(
            intent: Intent,
            id: Long,
            ok: Boolean,
            resultCode: Int,
        ) {
            val part = intent.getIntExtra(MessagingIntents.EXTRA_PART, 0)
            val parts = intent.getIntExtra(MessagingIntents.EXTRA_PARTS, 1)
            if (ok) {
                if (part >= parts - 1) sms.markSent(id)
                return
            }
            Timber.w("SMS part %d of %d not sent: result %d", part + 1, parts, resultCode)
            sms.markFailed(id, intent.getIntExtra(EXTRA_ERROR_CODE, resultCode))
            notifier.sendFailed(intent.getLongExtra(MessagingIntents.EXTRA_THREAD_ID, NONE))
        }

        private suspend fun smsDelivered(
            intent: Intent,
            id: Long,
        ) {
            val pdu = intent.getByteArrayExtra(EXTRA_PDU) ?: return
            val format = intent.getStringExtra(EXTRA_FORMAT)
            val status = SmsMessage.createFromPdu(pdu, format)?.status ?: return
            val part = intent.getIntExtra(MessagingIntents.EXTRA_PART, 0)
            val parts = intent.getIntExtra(MessagingIntents.EXTRA_PARTS, 1)
            DeliveryReports.combine(DeliveryReports.stateOf(status, format), part, parts)?.let { sms.markDelivery(id, it) }
        }

        // The carrier's M-Send.conf says whether it took the message; without one the service's result decides.
        private suspend fun mmsSent(
            intent: Intent,
            id: Long,
            ok: Boolean,
        ) {
            intent.getStringExtra(MessagingIntents.EXTRA_FILE)?.let(files::delete)
            val conf = intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA)?.let(::sendConf)
            if (ok && conf?.isAccepted != false) {
                mms.markSent(id, conf?.messageId, intent.getBooleanExtra(MessagingIntents.EXTRA_DELIVERY_REPORT, false))
            } else {
                Timber.w("MMS not sent: accepted %s", conf?.isAccepted)
                mms.markFailed(id)
                notifier.sendFailed(intent.getLongExtra(MessagingIntents.EXTRA_THREAD_ID, NONE))
            }
        }

        private suspend fun mmsDownloaded(
            intent: Intent,
            id: Long,
            ok: Boolean,
        ) {
            val file = intent.getStringExtra(MessagingIntents.EXTRA_FILE) ?: return
            val subscription =
                intent.getIntExtra(MessagingIntents.EXTRA_SUBSCRIPTION, MessagingIntents.NO_SUBSCRIPTION).takeIf {
                    it !=
                        MessagingIntents.NO_SUBSCRIPTION
                }
            val stored = downloader.downloaded(id, file, subscription, ok)
            if (stored is AppResult.Success) notifier.stored(stored.value, senderName = null)
        }

        private fun sendConf(bytes: ByteArray): SendConf? =
            try {
                MmsPduParser.parse(bytes) as? SendConf
            } catch (e: MalformedPduException) {
                Timber.w(e, "MMS send answer not readable")
                null
            }

        private companion object {
            const val NONE = -1L

            // Extras Android adds to the results (SmsManager.sendMultipartTextMessage and delivery reports).
            const val EXTRA_ERROR_CODE = "errorCode"
            const val EXTRA_PDU = "pdu"
            const val EXTRA_FORMAT = "format"
        }
    }
