package io.github.jeshiomurmu.opencaller.feature.messages.receive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.MessagePipeline
import io.github.jeshiomurmu.opencaller.feature.messages.MessagingWork
import timber.log.Timber
import javax.inject.Inject

/**
 * `SMS_DELIVER`: Android gives each received SMS to the default SMS app only, which must store it
 * (developer.android.com, `Telephony.Sms.Intents.SMS_DELIVER_ACTION`). Only the system can send it
 * (`BROADCAST_SMS` in the manifest). The [MessagePipeline] runs under `goAsync()` and ends within 10 s.
 */
@AndroidEntryPoint
class SmsDeliverReceiver : BroadcastReceiver() {
    @Inject
    lateinit var pipeline: MessagePipeline

    @Inject
    lateinit var work: MessagingWork

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val sms =
            runCatching { IncomingSmsReader.read(intent) }
                .onFailure { Timber.e(it, "Received SMS not readable") }
                .getOrNull() ?: return
        work.forBroadcast(goAsync()) { pipeline.receive(sms) }
    }
}
