package io.github.jeshiomurmu.opencaller.feature.voip

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons

/**
 * The app call's notification: a ringing call (high importance, the ringtone, a full-screen intent to
 * [VoipCallActivity] over the lock screen, Answer and Decline) or the call in progress (Hang up). On Android 12+ it
 * uses the platform's call style.
 */
internal class VoipNotifications(
    private val context: Context,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** The notification for [ui]. */
    fun build(ui: AppCallUi): Notification {
        createChannels()
        val ringing = ui.phase == AppCallPhase.RINGING_IN
        val who = ui.name ?: ui.number.orEmpty()
        val open = activity(VoipCallActivity.ACTION_SHOW, REQUEST_OPEN)
        val builder =
            Notification
                .Builder(context, if (ringing) CHANNEL_INCOMING else CHANNEL_ONGOING)
                .setSmallIcon(OpenCallerIcons.Calls)
                .setContentTitle(who)
                .setContentText(context.getString(if (ringing) R.string.voip_incoming else R.string.voip_ongoing))
                .setCategory(Notification.CATEGORY_CALL)
                .setOngoing(true)
                .setContentIntent(open)
        if (ringing) builder.setFullScreenIntent(open, true)
        val hangUp = service(VoipCallService.ACTION_HANG_UP, REQUEST_HANG_UP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val person =
                Person
                    .Builder()
                    .setName(who)
                    .setImportant(true)
                    .build()
            builder.setStyle(
                if (ringing) {
                    Notification.CallStyle.forIncomingCall(person, hangUp, activity(VoipCallActivity.ACTION_ANSWER, REQUEST_ANSWER))
                } else {
                    Notification.CallStyle.forOngoingCall(person, hangUp)
                },
            )
        } else {
            if (ringing) builder.addAction(action(R.string.voip_answer, activity(VoipCallActivity.ACTION_ANSWER, REQUEST_ANSWER)))
            builder.addAction(action(if (ringing) R.string.voip_decline else R.string.voip_hang_up, hangUp))
        }
        return builder.build().apply { if (ringing) flags = flags or Notification.FLAG_INSISTENT }
    }

    /** Posts [ui]'s notification without the service (Android refused a background foreground-service start). */
    fun show(ui: AppCallUi) {
        manager?.notify(NOTIFICATION_ID, build(ui))
    }

    /** Removes the notification. */
    fun clear() {
        manager?.cancel(NOTIFICATION_ID)
    }

    private fun createChannels() {
        val ringtone =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        manager?.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_INCOMING,
                    context.getString(R.string.voip_channel_incoming),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.voip_channel_incoming_description)
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), ringtone)
                    enableVibration(true)
                },
                NotificationChannel(CHANNEL_ONGOING, context.getString(R.string.voip_channel_ongoing), NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    private fun action(
        label: Int,
        intent: PendingIntent,
    ) = Notification.Action.Builder(null, context.getString(label), intent).build()

    private fun activity(
        action: String,
        request: Int,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            request,
            Intent(context, VoipCallActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun service(
        action: String,
        request: Int,
    ): PendingIntent =
        PendingIntent.getService(
            context,
            request,
            Intent(context, VoipCallService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Channels and ids. */
    companion object {
        /** Ringing app calls. */
        const val CHANNEL_INCOMING = "app_call_incoming"

        /** App calls in progress. */
        const val CHANNEL_ONGOING = "app_call_ongoing"

        /** Notification id. */
        const val NOTIFICATION_ID = 4_300
        private const val REQUEST_OPEN = 1
        private const val REQUEST_ANSWER = 2
        private const val REQUEST_HANG_UP = 3
    }
}
