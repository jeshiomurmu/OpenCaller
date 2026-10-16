package io.github.jeshiomurmu.opencaller.feature.callerid

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.ui.CallerCardState
import javax.inject.Inject
import javax.inject.Singleton

/** Intents between caller ID and the app. */
object CallerIdIntents {
    /** Extra on the app's launch intent: the E.164 number the user wants to report as spam. */
    const val EXTRA_REPORT_NUMBER = "io.github.jeshiomurmu.opencaller.extra.REPORT_NUMBER"
}

/**
 * Caller ID and after-call notifications (docs/architecture.md §4.1, §4.3). Nothing is posted when the user turned
 * notifications off; the after-call summary then stays in the in-app history (TC-ONB-006).
 */
@Singleton
class CallerIdNotifications
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val formatter: CallerCardFormatter,
    ) {
        private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

        /** Shows caller ID as a heads-up notification; returns false when notifications are off. */
        fun showCallerId(card: CallerCardModel): Boolean {
            if (!canNotify()) return false
            ensureChannels()
            val notification =
                Notification
                    .Builder(context, CHANNEL_CALLER_ID)
                    .setSmallIcon(OpenCallerIcons.Protection)
                    .setContentTitle(formatter.title(card))
                    .setContentText(formatter.summary(card))
                    .setStyle(Notification.BigTextStyle().bigText(formatter.summary(card)))
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setTimeoutAfter(CALLER_ID_TIMEOUT_MILLIS)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .build()
            manager.notify(CALLER_ID_NOTIFICATION, notification)
            return true
        }

        /** Removes the caller ID notification. */
        fun cancelCallerId() {
            manager.cancel(CALLER_ID_NOTIFICATION)
        }

        /**
         * Posts the after-call summary with up to three actions (Android shows no more): Block, then Not spam for a
         * flagged caller or Report otherwise, then Save contact. Tapping the summary opens the app.
         */
        fun showAfterCall(card: CallerCardModel): Boolean {
            val number = card.caller.number
            if (number == null || !canNotify()) return false
            ensureChannels()
            val id = afterCallId(number)
            val flagged = card.state == CallerCardState.SPAM || card.state == CallerCardState.SUSPICIOUS
            val builder =
                Notification
                    .Builder(context, CHANNEL_AFTER_CALL)
                    .setSmallIcon(OpenCallerIcons.Protection)
                    .setContentTitle(context.getString(R.string.after_call_title, formatter.title(card)))
                    .setContentText(formatter.summary(card))
                    .setCategory(Notification.CATEGORY_STATUS)
                    .setContentIntent(openApp(number, report = false, requestCode = id))
                    .setAutoCancel(true)
                    .addAction(broadcastAction(R.string.after_call_block, AfterCallActionReceiver.ACTION_BLOCK, number, id))
            if (flagged) {
                builder.addAction(broadcastAction(R.string.after_call_not_spam, AfterCallActionReceiver.ACTION_NOT_SPAM, number, id))
            } else {
                openApp(
                    number,
                    report = true,
                    requestCode = id + REPORT_OFFSET,
                )?.let { builder.addAction(action(R.string.after_call_report, it)) }
            }
            builder.addAction(action(R.string.after_call_save_contact, saveContact(number, id)))
            manager.notify(id, builder.build())
            return true
        }

        private fun canNotify(): Boolean {
            val permitted =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            return permitted && manager.areNotificationsEnabled()
        }

        private fun ensureChannels() {
            manager.createNotificationChannels(
                listOf(
                    NotificationChannel(
                        CHANNEL_CALLER_ID,
                        context.getString(R.string.channel_caller_id),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply { description = context.getString(R.string.channel_caller_id_description) },
                    NotificationChannel(
                        CHANNEL_AFTER_CALL,
                        context.getString(R.string.channel_after_call),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply { description = context.getString(R.string.channel_after_call_description) },
                ),
            )
        }

        private fun action(
            label: Int,
            intent: PendingIntent,
        ): Notification.Action = Notification.Action.Builder(null, context.getString(label), intent).build()

        private fun broadcastAction(
            label: Int,
            action: String,
            number: PhoneNumber,
            notificationId: Int,
        ): Notification.Action {
            val intent =
                Intent(context, AfterCallActionReceiver::class.java)
                    .setAction(action)
                    .putExtra(AfterCallActionReceiver.EXTRA_NUMBER, number.e164)
                    .putExtra(AfterCallActionReceiver.EXTRA_NOTIFICATION_ID, notificationId)
            val requestCode = notificationId + action.hashCode()
            return action(label, PendingIntent.getBroadcast(context, requestCode, intent, PENDING_FLAGS))
        }

        private fun openApp(
            number: PhoneNumber,
            report: Boolean,
            requestCode: Int,
        ): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            if (report) launch.putExtra(CallerIdIntents.EXTRA_REPORT_NUMBER, number.e164)
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, requestCode, launch, PENDING_FLAGS)
        }

        private fun saveContact(
            number: PhoneNumber,
            notificationId: Int,
        ): PendingIntent {
            val insert =
                Intent(ContactsContract.Intents.Insert.ACTION)
                    .setType(ContactsContract.RawContacts.CONTENT_TYPE)
                    .putExtra(ContactsContract.Intents.Insert.PHONE, number.e164)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return PendingIntent.getActivity(context, notificationId + SAVE_OFFSET, insert, PENDING_FLAGS)
        }

        private fun afterCallId(number: PhoneNumber): Int = AFTER_CALL_BASE + (number.e164.hashCode() and AFTER_CALL_MASK)

        /** Channel and notification ids. */
        companion object {
            /** Heads-up caller ID channel. */
            const val CHANNEL_CALLER_ID = "caller_id"

            /** After-call summary channel. */
            const val CHANNEL_AFTER_CALL = "after_call"

            /** Id of the caller ID notification (one call rings at a time). */
            const val CALLER_ID_NOTIFICATION = 1_001

            private const val AFTER_CALL_BASE = 0x10_0000
            private const val AFTER_CALL_MASK = 0xF_FFFF
            private const val REPORT_OFFSET = 1
            private const val SAVE_OFFSET = 2
            private const val CALLER_ID_TIMEOUT_MILLIS = 60_000L
            private const val PENDING_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        }
    }
