package io.github.jeshiomurmu.opencaller.feature.messages.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.model.Message
import io.github.jeshiomurmu.opencaller.core.model.MessageBox
import io.github.jeshiomurmu.opencaller.core.model.MmsDownload
import io.github.jeshiomurmu.opencaller.core.model.Participant
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.ConversationSource
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.IncomingMessageNotifier
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.IncomingSms
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.StoredMessage
import io.github.jeshiomurmu.opencaller.feature.messages.MessagesIntents
import io.github.jeshiomurmu.opencaller.feature.messages.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One notification per conversation (`Notification.MessagingStyle`) with its unread messages, Reply (a
 * `RemoteInput`) and Mark as read. On the lock screen only "New message" shows unless the user allows message
 * content there. Nothing is posted for the conversation on screen.
 */
@Suppress("TooManyFunctions") // The conversation notification's parts: style, actions, channels, public version.
@Singleton
class MessageNotifier
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val conversations: ConversationSource,
        private val names: ParticipantNames,
        private val active: ActiveThread,
    ) : IncomingMessageNotifier {
        private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

        override suspend fun stored(
            message: StoredMessage,
            senderName: String?,
        ) = refresh(message.threadId)

        override suspend fun unsaved(
            sms: IncomingSms,
            senderName: String?,
        ) {
            if (!canNotify()) return
            val notification =
                builder(CHANNEL_MESSAGES)
                    .setContentTitle(senderName ?: sms.address)
                    .setContentText(sms.body)
                    .setStyle(Notification.BigTextStyle().bigText(sms.body))
                    .setPublicVersion(publicVersion())
                    .build()
            manager.notify(TAG_UNSAVED, sms.address.hashCode(), notification)
        }

        /** Shows thread [threadId]'s unread messages, or removes its notification when none are left. */
        suspend fun refresh(threadId: Long) {
            // The conversation on screen is read as messages arrive.
            val showing = active.isShowing(threadId)
            if (showing) conversations.markRead(threadId)
            val unread = if (showing || !canNotify()) emptyList() else unreadIn(threadId)
            if (unread.isEmpty()) return cancel(threadId)
            val people = (conversations.participants(threadId) as? AppResult.Success)?.value.orEmpty()
            manager.notify(TAG_THREAD, threadId.toInt(), conversation(threadId, people, unread.takeLast(MAX_MESSAGES)))
        }

        private suspend fun unreadIn(threadId: Long): List<Message> =
            (conversations.thread(threadId) as? AppResult.Success)?.value.orEmpty().filter { !it.read && it.box == MessageBox.INBOX }

        /** The message to thread [threadId] could not be sent. */
        fun sendFailed(threadId: Long) {
            if (!canNotify() || threadId < 0) return
            val notification =
                builder(CHANNEL_FAILURES)
                    .setContentTitle(context.getString(R.string.messages_not_sent_title))
                    .setContentText(context.getString(R.string.messages_not_sent_body))
                    .setContentIntent(openThread(threadId))
                    .build()
            manager.notify(TAG_FAILED, threadId.toInt(), notification)
        }

        /** Removes thread [threadId]'s notifications. */
        fun cancel(threadId: Long) {
            manager.cancel(TAG_THREAD, threadId.toInt())
            manager.cancel(TAG_FAILED, threadId.toInt())
        }

        private suspend fun conversation(
            threadId: Long,
            people: List<Participant>,
            unread: List<Message>,
        ): Notification {
            val me = Person.Builder().setName(context.getString(R.string.messages_you)).build()
            val style = Notification.MessagingStyle(me)
            if (people.size > 1) {
                style.setGroupConversation(true)
                style.setConversationTitle(people.map { names.title(it) }.joinToString(", "))
            }
            val single = people.singleOrNull()?.let { names.title(it) }
            unread.forEach { message ->
                val from = message.from?.let { names.title(it) } ?: single ?: message.from?.address.orEmpty()
                style.addMessage(
                    Notification.MessagingStyle.Message(textOf(message), message.atMillis, Person.Builder().setName(from).build()),
                )
            }
            return builder(CHANNEL_MESSAGES)
                .setStyle(style)
                .setContentIntent(openThread(threadId))
                .setPublicVersion(publicVersion())
                .setWhen(unread.last().atMillis)
                .setShowWhen(true)
                .addAction(replyAction(threadId))
                .addAction(markReadAction(threadId))
                .build()
        }

        private fun textOf(message: Message): String =
            when {
                message.download != MmsDownload.DONE -> context.getString(R.string.messages_picture_message)
                message.body.isNotBlank() -> message.body
                message.attachments.isNotEmpty() -> context.getString(R.string.messages_picture)
                else -> message.subject.orEmpty()
            }

        private fun replyAction(threadId: Long): Notification.Action {
            val input = RemoteInput.Builder(MessageActionReceiver.KEY_REPLY).setLabel(context.getString(R.string.messages_reply)).build()
            val intent = action(MessageActionReceiver.ACTION_REPLY, threadId)
            // Mutable: Android adds the typed reply to it.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE or UPDATE else UPDATE
            return Notification.Action
                .Builder(
                    Icon.createWithResource(context, OpenCallerIcons.Send),
                    context.getString(R.string.messages_reply),
                    PendingIntent.getBroadcast(context, 0, intent, flags),
                ).addRemoteInput(input)
                .setAllowGeneratedReplies(true)
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                .build()
        }

        private fun markReadAction(threadId: Long): Notification.Action {
            val intent = PendingIntent.getBroadcast(context, 0, action(MessageActionReceiver.ACTION_MARK_READ, threadId), IMMUTABLE)
            return Notification.Action
                .Builder(Icon.createWithResource(context, OpenCallerIcons.Granted), context.getString(R.string.messages_mark_read), intent)
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .build()
        }

        // Each thread's actions have their own data address so their PendingIntents don't replace each other's.
        private fun action(
            action: String,
            threadId: Long,
        ): Intent =
            Intent(action, Uri.parse("opencaller-thread://$threadId"), context, MessageActionReceiver::class.java)
                .putExtra(MessagesIntents.EXTRA_THREAD_ID, threadId)

        private fun openThread(threadId: Long): PendingIntent? =
            MessagesIntents.openThread(context, threadId)?.let {
                PendingIntent.getActivity(context, threadId.toInt(), it, IMMUTABLE)
            }

        private fun builder(channel: String): Notification.Builder {
            ensureChannels()
            return Notification
                .Builder(context, channel)
                .setSmallIcon(OpenCallerIcons.Message)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
        }

        private fun publicVersion(): Notification =
            Notification
                .Builder(context, CHANNEL_MESSAGES)
                .setSmallIcon(OpenCallerIcons.Message)
                .setContentTitle(context.getString(R.string.messages_new_message))
                .build()

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
                        CHANNEL_MESSAGES,
                        context.getString(R.string.channel_messages),
                        NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = context.getString(R.string.channel_messages_description)
                        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    },
                    NotificationChannel(
                        CHANNEL_FAILURES,
                        context.getString(R.string.channel_message_failures),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = context.getString(R.string.channel_message_failures_description)
                    },
                ),
            )
        }

        /** Channels and tags. */
        companion object {
            /** Received messages. */
            const val CHANNEL_MESSAGES = "messages"

            /** Messages that couldn't be sent. */
            const val CHANNEL_FAILURES = "message_failures"

            /** Tag of a conversation's notification (its id is the thread id). */
            const val TAG_THREAD = "thread"

            private const val TAG_FAILED = "not_sent"
            private const val TAG_UNSAVED = "unsaved"
            private const val MAX_MESSAGES = 8
            private const val UPDATE = PendingIntent.FLAG_UPDATE_CURRENT
            private const val IMMUTABLE = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        }
    }
