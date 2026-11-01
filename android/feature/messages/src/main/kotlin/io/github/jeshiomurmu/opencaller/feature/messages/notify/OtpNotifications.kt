package io.github.jeshiomurmu.opencaller.feature.messages.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.text.format.DateFormat
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.model.MessageRisk
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.ReceivedDetails
import io.github.jeshiomurmu.opencaller.feature.messages.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The notification of a one-time code (TC-OTP-008): the code in large type with Copy. It is private, so a secure lock
 * screen set to hide sensitive content shows its public version ("Verification code", "Unlock to see the code").
 * With "Show all notification content" (Android's default) the system shows it in full; only the user changes that
 * (the channel's lock-screen visibility is the user's setting, an app's value is ignored; docs/phase.md issue 100).
 * It disappears when the code expires.
 */
@Singleton
class OtpNotifications
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val clock: Clock,
    ) {
        private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

        /** Shows the code in [details] (received in thread [threadId]); [open] opens the conversation. */
        fun show(
            threadId: Long,
            details: ReceivedDetails,
            open: PendingIntent?,
        ) {
            val otp = details.otp ?: return
            ensureChannel()
            val title =
                details.senderName?.let { context.getString(R.string.otp_notification_title_from, it) }
                    ?: context.getString(R.string.otp_notification_title)
            val expiry =
                otp.expiresAtMillis?.let {
                    context.getString(
                        R.string.otp_notification_expires,
                        DateFormat.getTimeFormat(context).format(it),
                    )
                }
            val warning = context.getString(R.string.otp_notification_never_share).takeIf { MessageRisk.OTP_DURING_CALL in details.risks }
            val builder =
                Notification
                    .Builder(context, CHANNEL_OTP)
                    .setSmallIcon(OpenCallerIcons.Privacy)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setPublicVersion(publicVersion())
                    .setContentTitle(title)
                    .setContentText(listOfNotNull(otp.code, expiry).joinToString(" · "))
                    .setStyle(Notification.BigTextStyle().bigText(listOfNotNull(otp.code, expiry, warning).joinToString("\n")))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .addAction(copyAction(threadId, otp.code))
            otp.expiresAtMillis?.let { builder.setTimeoutAfter((it - clock.nowMillis()).coerceAtLeast(MIN_TIMEOUT_MILLIS)) }
            manager.notify(MessageNotifier.TAG_THREAD, threadId.toInt(), builder.build())
        }

        private fun copyAction(
            threadId: Long,
            code: String,
        ): Notification.Action {
            val intent =
                Intent(OtpCopyReceiver.ACTION_COPY, "opencaller-code://$threadId".toUri(), context, OtpCopyReceiver::class.java)
                    .putExtra(OtpCopyReceiver.EXTRA_CODE, code)
            val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Action
                .Builder(Icon.createWithResource(context, OpenCallerIcons.Copy), context.getString(R.string.otp_copy), pending)
                .build()
        }

        private fun publicVersion(): Notification =
            Notification
                .Builder(context, CHANNEL_OTP)
                .setSmallIcon(OpenCallerIcons.Privacy)
                .setContentTitle(context.getString(R.string.otp_notification_title))
                .setContentText(context.getString(R.string.otp_notification_locked))
                .build()

        private fun ensureChannel() {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_OTP, context.getString(R.string.channel_otp), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.channel_otp_description)
                },
            )
        }

        /** The channel. */
        companion object {
            /** One-time codes. */
            const val CHANNEL_OTP = "otp_codes"
            private const val MIN_TIMEOUT_MILLIS = 60_000L
        }
    }
