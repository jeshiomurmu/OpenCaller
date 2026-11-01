package io.github.jeshiomurmu.opencaller.feature.otp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.telephony.otp.OtpAlarm
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The OTP guard's warnings (TC-OTP-005, TC-OTP-009). The call warning is a high-priority notification with a
 * full-screen intent, so it opens [OtpGuardActivity] over the lock screen; when OpenCaller already has a window (the
 * in-call screen, the caller-ID overlay) the warning opens at once too, which Android allows from a visible app or
 * one with the overlay permission. On Android 14+ the user can turn full-screen intents off
 * (`NotificationManager.canUseFullScreenIntent`); the notification then shows as a heads-up.
 */
@Singleton
class OtpGuardAlarm
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : OtpAlarm {
        private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

        override fun warnDuringCall(
            codeFrom: String?,
            flashCall: Boolean,
        ) {
            val intent = OtpGuardActivity.intent(context, codeFrom, flashCall)
            if (canNotify()) {
                val open =
                    PendingIntent.getActivity(
                        context,
                        GUARD_ID,
                        intent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                val notification =
                    builder()
                        .setContentTitle(context.getString(R.string.otp_guard_notification_title))
                        .setContentText(context.getString(R.string.otp_guard_notification_text))
                        .setCategory(Notification.CATEGORY_CALL)
                        .setContentIntent(open)
                        .setFullScreenIntent(open, true)
                        .build()
                manager.notify(GUARD_ID, notification)
            }
            openNow(intent)
        }

        override fun warnBurst(codes: Int) {
            if (!canNotify()) return
            val text = context.resources.getQuantityString(R.plurals.otp_burst_text, codes, codes)
            val notification =
                builder()
                    .setContentTitle(context.getString(R.string.otp_burst_title))
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setContentIntent(openApp())
                    .build()
            manager.notify(BURST_ID, notification)
        }

        /** Removes the call warning (the user has seen the screen). */
        fun cancelCallWarning() = manager.cancel(GUARD_ID)

        // Android refuses activity starts from the background unless the app is visible or may draw overlays; then
        // only the full-screen intent shows the warning.
        private fun openNow(intent: Intent) {
            try {
                context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                Timber.w(e, "OTP warning screen missing")
            }
        }

        // An app always sees its own launcher entry.
        @SuppressLint("QueryPermissionsNeeded")
        private fun openApp(): PendingIntent? =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, BURST_ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }

        private fun builder(): Notification.Builder {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_GUARD,
                    context.getString(R.string.channel_otp_guard),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.channel_otp_guard_description)
                },
            )
            return Notification
                .Builder(context, CHANNEL_GUARD)
                .setSmallIcon(OpenCallerIcons.Warning)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
        }

        private fun canNotify(): Boolean {
            val permitted =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            return permitted && manager.areNotificationsEnabled()
        }

        /** Channel and notification ids. */
        companion object {
            /** Warnings of the OTP guard. */
            const val CHANNEL_GUARD = "otp_guard"
            internal const val GUARD_ID = 7_301
            internal const val BURST_ID = 7_302
        }
    }
