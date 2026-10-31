package io.github.jeshiomurmu.opencaller.feature.messages.send

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.StoredMessage

/**
 * The PendingIntents handed to `SmsManager` for send, delivery and download results. They name
 * [MessageStatusReceiver] explicitly and are mutable: Android adds the results to them (the delivery report PDU,
 * the radio's error code, the carrier's MMS answer). Each one has its own data address, so the PendingIntents of a
 * message's parts don't replace each other.
 */
internal object MessagingIntents {
    const val ACTION_SMS_SENT = "io.github.jeshiomurmu.opencaller.action.SMS_SENT"
    const val ACTION_SMS_DELIVERED = "io.github.jeshiomurmu.opencaller.action.SMS_DELIVERED"
    const val ACTION_MMS_SENT = "io.github.jeshiomurmu.opencaller.action.MMS_SENT"
    const val ACTION_MMS_DOWNLOADED = "io.github.jeshiomurmu.opencaller.action.MMS_DOWNLOADED"
    const val ACTION_MMS_ANSWERED = "io.github.jeshiomurmu.opencaller.action.MMS_ANSWERED"

    const val EXTRA_ID = "io.github.jeshiomurmu.opencaller.extra.MESSAGE_ID"
    const val EXTRA_THREAD_ID = "io.github.jeshiomurmu.opencaller.extra.THREAD_ID"
    const val EXTRA_PART = "io.github.jeshiomurmu.opencaller.extra.PART"
    const val EXTRA_PARTS = "io.github.jeshiomurmu.opencaller.extra.PARTS"
    const val EXTRA_FILE = "io.github.jeshiomurmu.opencaller.extra.PDU_FILE"
    const val EXTRA_SUBSCRIPTION = "io.github.jeshiomurmu.opencaller.extra.SUBSCRIPTION"
    const val EXTRA_DELIVERY_REPORT = "io.github.jeshiomurmu.opencaller.extra.DELIVERY_REPORT"

    /** The result of part [part] of [parts] of SMS [message] going out. */
    fun smsSent(
        context: Context,
        message: StoredMessage,
        part: Int,
        parts: Int,
    ): PendingIntent = smsResult(context, ACTION_SMS_SENT, message, part, parts)

    /** The delivery report of part [part] of [parts] of SMS [message]. */
    fun smsDelivered(
        context: Context,
        message: StoredMessage,
        part: Int,
        parts: Int,
    ): PendingIntent = smsResult(context, ACTION_SMS_DELIVERED, message, part, parts)

    /** The carrier's answer to MMS [id], sent from [file]. */
    fun mmsSent(
        context: Context,
        id: Long,
        threadId: Long,
        file: String,
        deliveryReport: Boolean,
    ): PendingIntent =
        broadcast(
            context,
            intent(context, ACTION_MMS_SENT, "mms/$id/sent")
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_THREAD_ID, threadId)
                .putExtra(EXTRA_FILE, file)
                .putExtra(EXTRA_DELIVERY_REPORT, deliveryReport),
        )

    /** The download of notice [id] into [file], with SIM [subscriptionId]. */
    fun mmsDownloaded(
        context: Context,
        id: Long,
        file: String,
        subscriptionId: Int?,
    ): PendingIntent =
        broadcast(
            context,
            intent(context, ACTION_MMS_DOWNLOADED, "mms/$id/downloaded")
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_FILE, file)
                .putExtra(EXTRA_SUBSCRIPTION, subscriptionId ?: NO_SUBSCRIPTION),
        )

    /** The download answer (M-NotifyResp.ind) in [file] went out; the file can go. */
    fun mmsAnswered(
        context: Context,
        file: String,
    ): PendingIntent = broadcast(context, intent(context, ACTION_MMS_ANSWERED, "mms/answer/$file").putExtra(EXTRA_FILE, file))

    /** No SIM given. */
    const val NO_SUBSCRIPTION = -1

    private fun smsResult(
        context: Context,
        action: String,
        message: StoredMessage,
        part: Int,
        parts: Int,
    ): PendingIntent =
        broadcast(
            context,
            intent(context, action, "sms/${message.key.id}/$part")
                .putExtra(EXTRA_ID, message.key.id)
                .putExtra(EXTRA_THREAD_ID, message.threadId)
                .putExtra(EXTRA_PART, part)
                .putExtra(EXTRA_PARTS, parts),
        )

    private fun intent(
        context: Context,
        action: String,
        path: String,
    ): Intent = Intent(action, "$SCHEME://$path".toUri(), context, MessageStatusReceiver::class.java)

    private fun broadcast(
        context: Context,
        intent: Intent,
    ): PendingIntent = PendingIntent.getBroadcast(context, 0, intent, mutableFlags())

    // Android 12+ needs the mutability stated; before, PendingIntents are mutable unless FLAG_IMMUTABLE is set.
    private fun mutableFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private const val SCHEME = "opencaller-message"
}
