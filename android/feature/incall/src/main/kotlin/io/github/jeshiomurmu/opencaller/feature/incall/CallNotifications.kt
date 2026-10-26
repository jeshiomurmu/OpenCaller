package io.github.jeshiomurmu.opencaller.feature.incall

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.InCallState
import io.github.jeshiomurmu.opencaller.core.telephony.incall.TrackedCall
import javax.inject.Inject
import javax.inject.Singleton
import io.github.jeshiomurmu.opencaller.core.ui.R as UiR

/**
 * The incoming-call and ongoing-call notifications of the default phone app (developer.android.com, `InCallService` →
 * "Showing the Incoming Call Notification"). The incoming one carries a full-screen intent: Android shows the in-call
 * screen over the lock screen when the phone isn't in use, and a heads-up notification with Answer and Decline when it
 * is. Telecom plays the ringtone (OpenCaller doesn't declare `IN_CALL_SERVICE_RINGING`), so both channels are silent.
 */
@Singleton
internal class CallNotifications
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val text: CallerText,
    ) {
        private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
        private var shownIncoming: Any? = null
        private var shownOngoing: Any? = null

        /** Shows, updates or removes both notifications for [state]; reposts only when what they say changed. */
        fun render(
            state: InCallState,
            callers: Map<String, CallerInfo>,
        ) {
            if (!canNotify()) return
            ensureChannels()
            val ringing = state.ringing
            val incoming = ringing?.let { listOf(it.id, text.title(it, callers[it.id]), text.risk(callers[it.id])) }
            if (incoming != shownIncoming) {
                shownIncoming = incoming
                if (ringing == null) manager.cancel(INCOMING_ID) else manager.notify(INCOMING_ID, incoming(ringing, callers[ringing.id]))
            }
            val primary = state.primary
            val ongoing = primary?.let { listOf(it.id, text.title(it, callers[it.id]), it.status, it.connectedAtMillis) }
            if (ongoing != shownOngoing) {
                shownOngoing = ongoing
                if (primary == null) manager.cancel(ONGOING_ID) else manager.notify(ONGOING_ID, ongoing(primary, callers[primary.id]))
            }
        }

        /** Removes both notifications (the in-call service is going away). */
        fun cancelAll() {
            shownIncoming = null
            shownOngoing = null
            manager.cancel(INCOMING_ID)
            manager.cancel(ONGOING_ID)
        }

        private fun incoming(
            call: TrackedCall,
            caller: CallerInfo?,
        ): Notification {
            val title = text.title(call, caller)
            val show = activity(InCallIntents.show(context), SHOW_REQUEST)
            val answer = activity(InCallIntents.answer(context, call.id), ANSWER_REQUEST)
            val decline = broadcast(CallActionReceiver.ACTION_DECLINE, call.id, DECLINE_REQUEST)
            val builder =
                Notification
                    .Builder(context, CHANNEL_INCOMING)
                    .setSmallIcon(OpenCallerIcons.Calls)
                    .setContentTitle(title)
                    .setContentText(
                        listOfNotNull(
                            context.getString(R.string.incall_incoming),
                            text.risk(caller),
                        ).joinToString(context.getString(UiR.string.risk_banner_separator)),
                    ).setCategory(Notification.CATEGORY_CALL)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setContentIntent(show)
                    .setFullScreenIntent(show, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setStyle(Notification.CallStyle.forIncomingCall(person(title), decline, answer))
            } else {
                builder.addAction(action(R.string.incall_decline, decline)).addAction(action(R.string.incall_answer, answer))
            }
            return builder.build()
        }

        private fun ongoing(
            call: TrackedCall,
            caller: CallerInfo?,
        ): Notification {
            val connected = call.status == CallStatus.ACTIVE && call.connectedAtMillis > 0
            return Notification
                .Builder(context, CHANNEL_ONGOING)
                .setSmallIcon(OpenCallerIcons.Calls)
                .setContentTitle(text.title(call, caller))
                .setContentText(context.getString(statusText(call.status)))
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(connected)
                .setWhen(if (connected) call.connectedAtMillis else 0)
                .setUsesChronometer(connected)
                .setContentIntent(activity(InCallIntents.show(context), SHOW_REQUEST))
                .addAction(action(R.string.incall_hang_up, broadcast(CallActionReceiver.ACTION_HANG_UP, call.id, HANG_UP_REQUEST)))
                .build()
        }

        private fun person(name: String): Person =
            Person
                .Builder()
                .setName(name)
                .setImportant(true)
                .build()

        private fun action(
            label: Int,
            intent: PendingIntent,
        ): Notification.Action = Notification.Action.Builder(null, context.getString(label), intent).build()

        private fun activity(
            intent: Intent,
            requestCode: Int,
        ): PendingIntent = PendingIntent.getActivity(context, requestCode, intent, PENDING_FLAGS)

        private fun broadcast(
            action: String,
            callId: String,
            requestCode: Int,
        ): PendingIntent {
            val intent =
                Intent(context, CallActionReceiver::class.java)
                    .setAction(action)
                    .putExtra(InCallIntents.EXTRA_CALL_ID, callId)
            return PendingIntent.getBroadcast(context, requestCode, intent, PENDING_FLAGS)
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
                        CHANNEL_INCOMING,
                        context.getString(R.string.channel_incoming_calls),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = context.getString(R.string.channel_incoming_calls_description)
                        setSound(null, null)
                        enableVibration(false)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    },
                    NotificationChannel(
                        CHANNEL_ONGOING,
                        context.getString(R.string.channel_ongoing_calls),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = context.getString(R.string.channel_ongoing_calls_description) },
                ),
            )
        }

        /** Channel and notification ids. */
        companion object {
            /** Incoming calls (full-screen intent, no sound: Telecom rings). */
            const val CHANNEL_INCOMING = "incoming_calls"

            /** The call in progress. */
            const val CHANNEL_ONGOING = "ongoing_calls"

            /** The incoming-call notification. */
            const val INCOMING_ID = 2_001

            /** The ongoing-call notification. */
            const val ONGOING_ID = 2_002

            private const val SHOW_REQUEST = 1
            private const val ANSWER_REQUEST = 2
            private const val DECLINE_REQUEST = 3
            private const val HANG_UP_REQUEST = 4
            private const val PENDING_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

/** What the in-call screen and the ongoing notification say about a call's state. */
internal fun statusText(status: CallStatus): Int =
    when (status) {
        CallStatus.RINGING -> R.string.incall_incoming
        CallStatus.DIALING, CallStatus.CONNECTING -> R.string.incall_calling
        CallStatus.HOLDING -> R.string.incall_on_hold
        CallStatus.SELECT_SIM -> R.string.incall_choose_sim
        CallStatus.DISCONNECTING, CallStatus.DISCONNECTED -> R.string.incall_ended
        CallStatus.ACTIVE, CallStatus.OTHER -> R.string.incall_ongoing
    }
