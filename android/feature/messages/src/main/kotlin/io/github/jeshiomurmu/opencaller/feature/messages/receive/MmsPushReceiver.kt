package io.github.jeshiomurmu.opencaller.feature.messages.receive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.DeliveryReports
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.DeviceMmsStore
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.DeliveryInd
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.MalformedPduException
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.MmsPdu
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.MmsPduParser
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.NotificationInd
import io.github.jeshiomurmu.opencaller.feature.messages.MessagingWork
import io.github.jeshiomurmu.opencaller.feature.messages.notify.MessageNotifier
import io.github.jeshiomurmu.opencaller.feature.messages.send.MmsDownloader
import timber.log.Timber
import javax.inject.Inject

/**
 * `WAP_PUSH_DELIVER` with `application/vnd.wap.mms-message`: MMS download notices and delivery reports, given to the
 * default SMS app only (`BROADCAST_WAP_PUSH` in the manifest). A notice is stored at once (the conversation shows
 * "Downloading…") and its download starts; a delivery report updates the sent message.
 */
@AndroidEntryPoint
class MmsPushReceiver : BroadcastReceiver() {
    @Inject
    lateinit var store: DeviceMmsStore

    @Inject
    lateinit var downloader: MmsDownloader

    @Inject
    lateinit var notifier: MessageNotifier

    @Inject
    lateinit var work: MessagingWork

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val pdu = intent.getByteArrayExtra(EXTRA_DATA)?.let(::parse) ?: return
        val subscription = IncomingSmsReader.subscriptionOf(intent)
        work.forBroadcast(goAsync()) {
            when (pdu) {
                is NotificationInd -> notice(pdu, subscription)
                is DeliveryInd -> pdu.messageId?.let { store.markDelivery(it, DeliveryReports.stateOfMms(pdu.status)) }
                else -> Timber.i("MMS push of another type ignored")
            }
        }
    }

    private suspend fun notice(
        notice: NotificationInd,
        subscription: Int?,
    ) {
        val stored = store.saveNotice(notice, subscription)
        if (stored is AppResult.Success) {
            val started = downloader.download(stored.value.key.id)
            if (started is AppResult.Failure) notifier.stored(stored.value, senderName = null)
        } else {
            Timber.e("MMS notice not stored")
        }
    }

    private fun parse(bytes: ByteArray): MmsPdu? =
        try {
            MmsPduParser.parse(bytes)
        } catch (e: MalformedPduException) {
            Timber.w(e, "MMS push not readable")
            null
        }

    private companion object {
        // The push's payload (developer.android.com, Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION).
        const val EXTRA_DATA = "data"
    }
}
