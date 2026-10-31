package io.github.jeshiomurmu.opencaller.feature.messages.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.model.DeliveryState
import io.github.jeshiomurmu.opencaller.core.model.Message
import io.github.jeshiomurmu.opencaller.core.model.MessageBox
import io.github.jeshiomurmu.opencaller.core.model.MessageKey
import io.github.jeshiomurmu.opencaller.core.model.MessagingSim
import io.github.jeshiomurmu.opencaller.core.model.MmsDownload
import io.github.jeshiomurmu.opencaller.core.model.Participant
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.ConversationSource
import io.github.jeshiomurmu.opencaller.feature.messages.notify.ParticipantNames
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where a message of the user's stands. */
enum class MessageStatus {
    /** A received message. */
    NONE,

    /** Being sent. */
    SENDING,

    /** Accepted by the network. */
    SENT,

    /** Delivered (the delivery report came back). */
    DELIVERED,

    /** Not sent or not delivered; the user can try again (SMS). */
    FAILED,
}

/** One message in the thread. [sender] names the sender of a received group message. */
data class ThreadItem(
    val key: MessageKey,
    val outgoing: Boolean,
    val sender: String?,
    val body: String,
    val subject: String?,
    val atMillis: Long,
    val status: MessageStatus,
    val images: List<String> = emptyList(),
    val download: MmsDownload = MmsDownload.DONE,
    val sizeBytes: Long = 0,
)

/** The thread screen. [simId] is the SIM the next message goes out with (`null`: the default). */
data class ThreadUiState(
    val loading: Boolean = true,
    val canRead: Boolean = true,
    val title: String = "",
    val isGroup: Boolean = false,
    val messages: List<ThreadItem> = emptyList(),
    val draft: String = "",
    val pictures: List<String> = emptyList(),
    val sims: List<MessagingSim> = emptyList(),
    val simId: Int? = null,
    val choosingSim: Boolean = false,
    val sending: Boolean = false,
    val failed: Boolean = false,
) {
    /** True when Send has something to send. */
    val canSend: Boolean get() = !sending && (draft.isNotBlank() || pictures.isNotEmpty())
}

/** What the user did in the thread. */
sealed interface ThreadEvent {
    /**
     * Show thread [threadId], with [draft] in the composer (a `sms_body` from another app). [recipients] are the people
     * of a new conversation: Android's store only knows a thread's people from its messages.
     */
    data class Open(
        val threadId: Long,
        val draft: String? = null,
        val recipients: List<String> = emptyList(),
    ) : ThreadEvent

    /** The composer's text changed. */
    data class Draft(
        val text: String,
    ) : ThreadEvent

    /** Pictures were picked. */
    data class AddPictures(
        val uris: List<String>,
    ) : ThreadEvent

    /** A picked picture was removed. */
    data class RemovePicture(
        val uri: String,
    ) : ThreadEvent

    /** Send with SIM [subscriptionId]. */
    data class PickSim(
        val subscriptionId: Int,
    ) : ThreadEvent

    /** The SIM chooser was closed without a choice. */
    data object CancelSim : ThreadEvent

    /** Send the draft. */
    data object Send : ThreadEvent

    /** Send failed message [key] again. */
    data class Retry(
        val key: MessageKey,
    ) : ThreadEvent

    /** Download MMS notice [key]. */
    data class Download(
        val key: MessageKey,
    ) : ThreadEvent

    /** The screen is in front ([visible]) or not: messages arriving while it is need no notification. */
    data class Visible(
        val visible: Boolean,
    ) : ThreadEvent

    /** The failure was read. */
    data object DismissFailure : ThreadEvent
}

/** What the thread screen does while it is in front: the conversation counts as read and has no notification. */
interface ThreadPresence {
    /** Thread [threadId] is in front; its messages are read. */
    suspend fun shown(threadId: Long)

    /** No thread is in front. */
    fun hidden()
}

private data class Header(
    val title: String,
    val recipients: List<String>,
    val isGroup: Boolean,
)

/**
 * One conversation (TC-SMS-001/002/003): its messages oldest first with their sent and delivered states, pictures
 * and download buttons, and the composer (text, pictures, SIM). Several people or a picture make the message an MMS.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ThreadViewModel
    @Inject
    constructor(
        private val conversations: ConversationSource,
        private val names: ParticipantNames,
        private val sending: MessageSending,
        private val presence: ThreadPresence,
        dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val threadId = MutableStateFlow<Long?>(null)
        private val header = MutableStateFlow(Header("", emptyList(), isGroup = false))
        private val composer = MutableStateFlow(ThreadUiState(loading = false))

        /** Screen state. */
        val uiState: StateFlow<ThreadUiState> =
            combine(threadId.filterNotNull().flatMapLatest(::messages), header, composer) { loaded, head, local ->
                local.copy(
                    loading = loaded.loading,
                    canRead = loaded.canRead,
                    messages = loaded.messages,
                    title = head.title,
                    isGroup = head.isGroup,
                    failed = loaded.failed || local.failed,
                )
            }.flowOn(dispatchers.default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ThreadUiState())

        /** Handles [event]. */
        fun onEvent(event: ThreadEvent) {
            when (event) {
                is ThreadEvent.Open -> open(event)
                is ThreadEvent.PickSim -> pickSim(event.subscriptionId)
                ThreadEvent.Send -> send()
                is ThreadEvent.Retry -> act { sending.retry(event.key) }
                is ThreadEvent.Download -> act { sending.download(event.key.id) }
                is ThreadEvent.Visible -> visible(event.visible)
                else -> composer.update { edited(it, event) }
            }
        }

        // The events that only change the composer.
        private fun edited(
            state: ThreadUiState,
            event: ThreadEvent,
        ): ThreadUiState =
            when (event) {
                is ThreadEvent.Draft -> state.copy(draft = event.text)
                is ThreadEvent.AddPictures -> state.copy(pictures = (state.pictures + event.uris).distinct().take(MAX_PICTURES))
                is ThreadEvent.RemovePicture -> state.copy(pictures = state.pictures - event.uri)
                ThreadEvent.CancelSim -> state.copy(choosingSim = false)
                ThreadEvent.DismissFailure -> state.copy(failed = false)
                else -> state
            }

        private fun open(event: ThreadEvent.Open) {
            val id = event.threadId
            if (threadId.value == id) return
            threadId.value = id
            val sims = sending.sims()
            composer.update {
                it.copy(draft = event.draft ?: it.draft, sims = sims.options, simId = it.simId ?: sims.preferred?.subscriptionId)
            }
            viewModelScope.launch {
                // A conversation without messages yet has its people only in the request that opened it.
                val people =
                    conversations
                        .participants(id)
                        .getOrNull()
                        .orEmpty()
                        .ifEmpty { event.recipients.map(names::participant) }
                header.value = Header(people.map { names.title(it) }.joinToString(", "), people.map(Participant::address), people.size > 1)
            }
        }

        private fun messages(id: Long): Flow<ThreadUiState> {
            if (!conversations.canRead()) return flowOf(ThreadUiState(loading = false, canRead = false))
            return conversations.observeThread(id).map { result ->
                when (result) {
                    is AppResult.Success -> ThreadUiState(loading = false, messages = result.value.map { itemOf(it) })
                    is AppResult.Failure -> ThreadUiState(loading = false, failed = true)
                }
            }
        }

        private suspend fun itemOf(message: Message): ThreadItem =
            ThreadItem(
                key = message.key,
                outgoing = message.isOutgoing,
                sender = message.from?.let { names.title(it) },
                body = message.body,
                subject = message.subject,
                atMillis = message.atMillis,
                status = statusOf(message),
                images = message.attachments.filter { it.isImage }.map { it.uri },
                download = message.download,
                sizeBytes = message.sizeBytes,
            )

        private fun pickSim(subscriptionId: Int) {
            val wasAsking = composer.value.choosingSim
            composer.update { it.copy(simId = subscriptionId, choosingSim = false) }
            if (wasAsking) send()
        }

        // With several SIMs and no default, the first send asks which one.
        private fun send() {
            val state = composer.value
            val recipients = header.value.recipients
            if (!state.canSend || recipients.isEmpty()) return
            if (state.simId == null && sending.sims().needsChoice) {
                composer.update { it.copy(choosingSim = true) }
                return
            }
            composer.update { it.copy(sending = true) }
            viewModelScope.launch {
                val result = sending.send(recipients, state.draft.trim(), state.pictures, state.simId)
                composer.update {
                    if (result is AppResult.Success) {
                        it.copy(sending = false, draft = "", pictures = emptyList())
                    } else {
                        it.copy(sending = false, failed = true)
                    }
                }
            }
        }

        private fun act(block: suspend () -> AppResult<Unit>) {
            viewModelScope.launch { if (block() is AppResult.Failure) composer.update { it.copy(failed = true) } }
        }

        private fun visible(visible: Boolean) {
            val id = threadId.value ?: return
            if (visible) viewModelScope.launch { presence.shown(id) } else presence.hidden()
        }

        override fun onCleared() {
            presence.hidden()
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val MAX_PICTURES = 5
        }
    }

/** The status shown under a message. */
internal fun statusOf(message: Message): MessageStatus =
    when {
        !message.isOutgoing -> MessageStatus.NONE
        message.box == MessageBox.FAILED || message.delivery == DeliveryState.FAILED -> MessageStatus.FAILED
        message.box == MessageBox.OUTBOX || message.box == MessageBox.DRAFT -> MessageStatus.SENDING
        message.delivery == DeliveryState.DELIVERED -> MessageStatus.DELIVERED
        else -> MessageStatus.SENT
    }
