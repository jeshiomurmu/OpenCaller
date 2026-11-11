package io.github.jeshiomurmu.opencaller.feature.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantCallNotices
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import javax.inject.Inject

/**
 * "The assistant is answering a call" (TC-AST-001): no number in the notification (the push carried none); tapping
 * it opens the live screen, which learns the caller from the agent.
 */
class AssistantCallNotifier
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : AssistantCallNotices {
        override fun show(
            callId: String,
            startedAtMillis: Long,
        ) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.assistant_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.assistant_channel_description)
                },
            )
            val open =
                PendingIntent.getActivity(
                    context,
                    callId.hashCode(),
                    LiveScreeningActivity.intent(context, callId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val notification =
                Notification
                    .Builder(context, CHANNEL)
                    .setSmallIcon(OpenCallerIcons.Voicemail)
                    .setContentTitle(context.getString(R.string.assistant_notification_title))
                    .setContentText(context.getString(R.string.assistant_notification_text))
                    .setCategory(Notification.CATEGORY_CALL)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setWhen(startedAtMillis)
                    .setShowWhen(true)
                    .build()
            manager.notify(callId, NOTIFICATION_ID, notification)
        }

        /** Channel and id. */
        companion object {
            /** Notification channel of screened calls. */
            const val CHANNEL = "assistant_calls"

            /** Notification id (tagged with the call's id). */
            const val NOTIFICATION_ID = 4_500
        }
    }

/** The live screen's intent for call [callId]. */
internal fun liveIntent(
    context: Context,
    callId: String,
): Intent =
    Intent(context, LiveScreeningActivity::class.java)
        .putExtra(LiveScreeningViewModel.EXTRA_CALL_ID, callId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
