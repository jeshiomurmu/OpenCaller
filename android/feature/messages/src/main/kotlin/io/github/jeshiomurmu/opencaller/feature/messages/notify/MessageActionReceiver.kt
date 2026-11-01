package io.github.jeshiomurmu.opencaller.feature.messages.notify

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.ConversationSource
import io.github.jeshiomurmu.opencaller.feature.messages.MessagesIntents
import io.github.jeshiomurmu.opencaller.feature.messages.MessagingWork
import io.github.jeshiomurmu.opencaller.feature.messages.send.OutgoingMessages
import javax.inject.Inject

/**
 * Reply and Mark as read on a conversation's notification. Not exported: only OpenCaller's own PendingIntents reach
 * it. A reply goes to everyone in the conversation, then the conversation counts as read.
 */
@AndroidEntryPoint
class MessageActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var outgoing: OutgoingMessages

    @Inject
    lateinit var conversations: ConversationSource

    @Inject
    lateinit var notifier: MessageNotifier

    @Inject
    lateinit var work: MessagingWork

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val threadId = MessagesIntents.threadOf(intent) ?: return
        val reply =
            RemoteInput
                .getResultsFromIntent(intent)
                ?.getCharSequence(KEY_REPLY)
                ?.toString()
                ?.takeIf(String::isNotBlank)
        when (intent.action) {
            ACTION_REPLY -> if (reply != null) work.forBroadcast(goAsync()) { replied(threadId, reply) }
            ACTION_MARK_READ -> work.forBroadcast(goAsync()) { read(threadId) }
        }
    }

    private suspend fun replied(
        threadId: Long,
        text: String,
    ) {
        val sent = outgoing.reply(threadId, text, subscriptionId = null)
        if (sent is AppResult.Failure) notifier.sendFailed(threadId)
        read(threadId)
    }

    private suspend fun read(threadId: Long) {
        conversations.markRead(threadId)
        notifier.cancel(threadId)
    }

    /** Actions and the reply's key. */
    companion object {
        /** Reply to the conversation. */
        const val ACTION_REPLY = "io.github.jeshiomurmu.opencaller.action.MESSAGE_REPLY"

        /** Mark the conversation read. */
        const val ACTION_MARK_READ = "io.github.jeshiomurmu.opencaller.action.MESSAGE_MARK_READ"

        /** The `RemoteInput` key of the typed reply. */
        const val KEY_REPLY = "reply"
    }
}
