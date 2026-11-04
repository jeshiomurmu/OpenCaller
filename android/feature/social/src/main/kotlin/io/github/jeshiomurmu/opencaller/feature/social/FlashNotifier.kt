package io.github.jeshiomurmu.opencaller.feature.social

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.data.voip.FlashAlerts
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.model.FlashMessage
import javax.inject.Inject

/**
 * A received Flash message as a notification: "Flash from Asha" / "Running late". Tapping it opens the app. The
 * sender's name shows only when their profile allows it, otherwise their number.
 */
class FlashNotifier
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : FlashAlerts {
        override fun show(message: FlashMessage) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.flash_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.flash_channel_description)
                },
            )
            val open =
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                    PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                }
            val notification =
                Notification
                    .Builder(context, CHANNEL)
                    .setSmallIcon(OpenCallerIcons.Notifications)
                    .setContentTitle(context.getString(R.string.flash_from, message.name ?: message.number.e164))
                    .setContentText(context.getString(message.kind.label()))
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .setWhen(message.atMillis)
                    .setShowWhen(true)
                    .apply { open?.let(::setContentIntent) }
                    .build()
            manager.notify(message.id, NOTIFICATION_ID, notification)
        }

        /** Channel and id. */
        companion object {
            /** Notification channel of Flash messages. */
            const val CHANNEL = "flash"

            /** Notification id (one notification per message, tagged with its id). */
            const val NOTIFICATION_ID = 4_200
        }
    }
