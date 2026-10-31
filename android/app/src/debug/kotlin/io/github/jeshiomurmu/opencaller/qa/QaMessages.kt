package io.github.jeshiomurmu.opencaller.qa

import android.content.Intent
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.data.MessageMetaRepository
import io.github.jeshiomurmu.opencaller.core.model.MessageKind
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.ConversationSource
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.MessageAddresses
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Debug builds only: what `scripts/qa/sms.sh` checks for TC-SMS-001/002, read through the same message store as the
 * screens.
 *
 * - `messages --es address A`: the conversations with A, as "ok thread=T count=N unread=U in=I
 *   out=BOX/DELIVERY/LENGTH meta=CATEGORY body=TEXT" (T, the meta row and the text of the newest received message, the
 *   state of the newest sent one), or "ok none" when there is no conversation with A.
 */
class QaMessages
    @Inject
    constructor(
        private val conversations: ConversationSource,
        private val meta: MessageMetaRepository,
    ) {
        /** Runs [cmd], or returns `null` when it isn't one of these commands. */
        suspend fun run(
            cmd: String?,
            intent: Intent,
        ): String? =
            when (cmd) {
                "messages" -> describe(intent.getStringExtra("address").orEmpty())
                else -> null
            }

        // Every conversation with the address counts: Android may keep "+1555…" and "1555…" in separate threads.
        private suspend fun describe(address: String): String {
            val all = conversations.observeConversations().first()
            val matching =
                all.getOrNull().orEmpty().filter { conversation ->
                    conversation.participants.any { MessageAddresses.same(it.address, address) }
                }
            return when {
                all is AppResult.Failure -> "error ${all.error}"
                matching.isEmpty() -> "ok none"
                else -> describe(matching.map { it.threadId }, matching.sumOf { it.unreadCount })
            }
        }

        private suspend fun describe(
            threads: List<Long>,
            unread: Int,
        ): String {
            val messages = threads.flatMap { conversations.thread(it).getOrNull().orEmpty() }.sortedBy { it.atMillis }
            val received = messages.lastOrNull { !it.isOutgoing }
            val sent = messages.lastOrNull { it.isOutgoing }
            val row = received?.takeIf { it.key.kind == MessageKind.SMS }?.let { meta.find(it.key.id).getOrNull() }
            return "ok thread=${received?.threadId ?: threads.first()} count=${messages.size} unread=$unread " +
                "in=${messages.count { !it.isOutgoing }} out=${sent?.let { "${it.box}/${it.delivery}/${it.body.length}" } ?: "none"} " +
                "meta=${row?.category ?: "none"} body=${received?.body.orEmpty()}"
        }
    }
