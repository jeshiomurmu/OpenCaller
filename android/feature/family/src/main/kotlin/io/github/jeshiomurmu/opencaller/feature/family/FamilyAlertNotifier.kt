package io.github.jeshiomurmu.opencaller.feature.family

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyAlertNotices
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.model.FamilyAlert
import javax.inject.Inject

/**
 * A member's alert on the admin's phone (TC-FAM-002): "Family alert: Mum" and what happened, without any number,
 * code or message. Tapping it opens the app.
 */
class FamilyAlertNotifier
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : FamilyAlertNotices {
        override fun show(alert: FamilyAlert) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.family_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.family_channel_description)
                },
            )
            val open =
                context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                    PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                }
            val text = context.getString(alert.kind.text())
            val notification =
                Notification
                    .Builder(context, CHANNEL)
                    .setSmallIcon(OpenCallerIcons.Protection)
                    .setContentTitle(context.getString(R.string.family_notification_title, alert.memberLabel))
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setAutoCancel(true)
                    .setWhen(alert.atMillis)
                    .setShowWhen(true)
                    .apply { open?.let(::setContentIntent) }
                    .build()
            manager.notify(alert.id, NOTIFICATION_ID, notification)
        }

        /** Channel and id. */
        companion object {
            /** Notification channel of family alerts. */
            const val CHANNEL = "family_alerts"

            /** Notification id (one notification per alert, tagged with its id). */
            const val NOTIFICATION_ID = 4_400
        }
    }
