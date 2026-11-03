package io.github.jeshiomurmu.opencaller.feature.settings.ghostcall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.model.GhostCallSetup
import io.github.jeshiomurmu.opencaller.feature.settings.R
import javax.inject.Inject

/**
 * The fake call's notification: a full-screen intent to [GhostCallActivity] (category `call`, high importance), so
 * it looks like an incoming call over the lock screen, or a heads-up while the phone is in use. It rings for
 * [RING_MILLIS] at most. Android 14+ lets the user turn full-screen intents off; then it stays a heads-up.
 */
class GhostCallNotifier
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) {
        /** Posts the notification for [setup]. */
        fun ring(setup: GhostCallSetup) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.ghost_call_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.ghost_call_channel_description)
                    // The screen plays the ringtone itself and only when the ringer is on.
                    setSound(null, null)
                },
            )
            val open = activityIntent(GhostCallActivity.ACTION_RING, REQUEST_RING)
            val notification =
                Notification
                    .Builder(context, CHANNEL)
                    .setSmallIcon(OpenCallerIcons.Calls)
                    .setContentTitle(setup.callerName.ifBlank { context.getString(R.string.ghost_call_default_name) })
                    .setContentText(setup.displayNumber.ifBlank { context.getString(R.string.ghost_call_incoming) })
                    .setCategory(Notification.CATEGORY_CALL)
                    .setOngoing(true)
                    .setFullScreenIntent(open, true)
                    .setContentIntent(open)
                    .setTimeoutAfter(RING_MILLIS)
                    .addAction(
                        Notification.Action
                            .Builder(
                                null,
                                context.getString(R.string.ghost_call_decline),
                                activityIntent(GhostCallActivity.ACTION_DECLINE, REQUEST_DECLINE),
                            ).build(),
                    ).addAction(
                        Notification.Action
                            .Builder(
                                null,
                                context.getString(R.string.ghost_call_answer),
                                activityIntent(GhostCallActivity.ACTION_ANSWER, REQUEST_ANSWER),
                            ).build(),
                    ).build()
            manager.notify(NOTIFICATION_ID, notification)
        }

        /** Removes the notification (answered, declined or timed out). */
        fun clear() {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }

        private fun activityIntent(
            action: String,
            request: Int,
        ): PendingIntent =
            PendingIntent.getActivity(
                context,
                request,
                Intent(context, GhostCallActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** Channel and timing. */
        companion object {
            /** Notification channel of the fake call. */
            const val CHANNEL = "ghost_call"

            /** Notification id. */
            const val NOTIFICATION_ID = 4_100

            /** How long the fake call rings. */
            const val RING_MILLIS = 45_000L
            private const val REQUEST_RING = 4_101
            private const val REQUEST_DECLINE = 4_102
            private const val REQUEST_ANSWER = 4_103
        }
    }
