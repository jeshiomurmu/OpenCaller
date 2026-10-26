package io.github.jeshiomurmu.opencaller.feature.messages.send

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.feature.messages.MessagingWork
import javax.inject.Inject

/**
 * Receives the results of OpenCaller's own sends, delivery reports and downloads ([MessagingIntents]). Not exported:
 * only the PendingIntents OpenCaller hands to `SmsManager` reach it.
 */
@AndroidEntryPoint
class MessageStatusReceiver : BroadcastReceiver() {
    @Inject
    lateinit var results: MessageResults

    @Inject
    lateinit var work: MessagingWork

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val code = resultCode
        work.forBroadcast(goAsync()) { results.handle(intent, code) }
    }
}
