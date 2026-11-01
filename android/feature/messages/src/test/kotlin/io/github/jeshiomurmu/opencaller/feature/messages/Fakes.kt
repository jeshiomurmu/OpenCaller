package io.github.jeshiomurmu.opencaller.feature.messages

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.Conversation
import io.github.jeshiomurmu.opencaller.core.model.Message
import io.github.jeshiomurmu.opencaller.core.model.MessageKey
import io.github.jeshiomurmu.opencaller.core.model.MessageKind
import io.github.jeshiomurmu.opencaller.core.model.MessagingSims
import io.github.jeshiomurmu.opencaller.core.model.Participant
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.ConversationSource
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.StoredMessage
import io.github.jeshiomurmu.opencaller.feature.messages.ui.MessageSending
import io.github.jeshiomurmu.opencaller.feature.messages.ui.ThreadPresence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Conversations kept in memory; [readable] stands for the role and READ_SMS. */
internal class FakeConversations(
    var readable: Boolean = true,
) : ConversationSource {
    val list = MutableStateFlow<List<Conversation>>(emptyList())
    val threads = MutableStateFlow<Map<Long, List<Message>>>(emptyMap())
    val people = mutableMapOf<Long, List<Participant>>()
    val markedRead = mutableListOf<Long>()
    val deleted = mutableListOf<Long>()
    var threadForAsked: Set<String>? = null
    var failDelete = false

    override fun canRead(): Boolean = readable

    override fun observeConversations(): Flow<AppResult<List<Conversation>>> = list.map { AppResult.Success(it) }

    override fun observeThread(threadId: Long): Flow<AppResult<List<Message>>> = threads.map { AppResult.Success(it[threadId].orEmpty()) }

    override suspend fun thread(threadId: Long): AppResult<List<Message>> = AppResult.Success(threads.value[threadId].orEmpty())

    override suspend fun participants(threadId: Long): AppResult<List<Participant>> = AppResult.Success(people[threadId].orEmpty())

    override suspend fun threadFor(addresses: Set<String>): AppResult<Long> {
        threadForAsked = addresses
        return AppResult.Success(NEW_THREAD)
    }

    override suspend fun markRead(threadId: Long): AppResult<Int> {
        markedRead += threadId
        return AppResult.Success(1)
    }

    override suspend fun delete(threadId: Long): AppResult<Int> {
        if (failDelete) return AppResult.Failure(AppError.Storage)
        deleted += threadId
        return AppResult.Success(1)
    }

    companion object {
        const val NEW_THREAD = 99L
    }
}

/** Records what was sent. */
internal class FakeSending(
    var sims: MessagingSims = MessagingSims(),
    var result: AppResult<StoredMessage> = AppResult.Success(StoredMessage(MessageKey(MessageKind.SMS, 1), 1)),
) : MessageSending {
    val sent = mutableListOf<List<Any?>>()
    val retried = mutableListOf<MessageKey>()
    val downloaded = mutableListOf<Long>()

    override fun sims(): MessagingSims = sims

    override suspend fun send(
        recipients: List<String>,
        text: String,
        pictures: List<String>,
        subscriptionId: Int?,
    ): AppResult<StoredMessage> {
        sent += listOf(recipients, text, pictures, subscriptionId)
        return result
    }

    override suspend fun retry(key: MessageKey): AppResult<Unit> {
        retried += key
        return AppResult.Success(Unit)
    }

    override suspend fun download(id: Long): AppResult<Unit> {
        downloaded += id
        return AppResult.Success(Unit)
    }
}

/** Records what the thread screen said about being in front. */
internal class FakePresence : ThreadPresence {
    val shown = mutableListOf<Long>()
    var hidden = 0

    override suspend fun shown(threadId: Long) {
        shown += threadId
    }

    override fun hidden() {
        hidden++
    }
}

/** A participant with a valid number. */
internal fun person(e164: String) = Participant(e164, PhoneNumber(e164))
