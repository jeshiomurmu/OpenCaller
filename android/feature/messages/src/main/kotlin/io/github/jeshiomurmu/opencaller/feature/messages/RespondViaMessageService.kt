package io.github.jeshiomurmu.opencaller.feature.messages

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyManager
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.feature.messages.receive.IncomingSmsReader
import io.github.jeshiomurmu.opencaller.feature.messages.send.OutgoingMessages
import timber.log.Timber
import javax.inject.Inject

/**
 * `ACTION_RESPOND_VIA_MESSAGE`: the phone app (OpenCaller's own in-call screen or another one) declines a call with
 * a quick reply and asks the default SMS app to send it without any screen (developer.android.com,
 * `TelephonyManager.ACTION_RESPOND_VIA_MESSAGE`). Only apps with `SEND_RESPOND_VIA_MESSAGE` (Telecom) may start it.
 */
@AndroidEntryPoint
class RespondViaMessageService : Service() {
    @Inject
    lateinit var outgoing: OutgoingMessages

    @Inject
    lateinit var work: MessagingWork

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.takeIf(String::isNotBlank)
        val recipients = MessageLinks.recipients(intent?.data?.schemeSpecificPart)
        if (intent?.action != TelephonyManager.ACTION_RESPOND_VIA_MESSAGE || text == null || recipients.isEmpty()) {
            Timber.w("Quick reply without a recipient or text")
            stopSelf(startId)
        } else {
            work.run(
                done = { stopSelf(startId) },
            ) { outgoing.send(recipients, text, emptyList(), IncomingSmsReader.subscriptionOf(intent)) }
        }
        return START_NOT_STICKY
    }
}
