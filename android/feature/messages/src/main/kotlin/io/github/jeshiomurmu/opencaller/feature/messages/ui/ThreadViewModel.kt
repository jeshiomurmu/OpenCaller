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
import io.github.jeshiomurmu.opencaller.core.model.MessageKind
import io.github.jeshiomurmu.opencaller.core.model.MessageMeta
import io.github.jeshiomurmu.opencaller.core.model.MessageRisk
import io.github.jeshiomurmu.opencaller.core.model.MessagingSim
import io.github.jeshiomurmu.opencaller.core.model.MmsDownload
import io.github.jeshiomurmu.opencaller.core.model.Participant
import io.github.jeshiomurmu.opencaller.core.model.ScheduledMessage
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
    val risky: Boolean = false,
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
    val scheduled: List<ScheduledMessage> = emptyList(),
    val presets: List<SchedulePreset> = emptyList(),
) {
    /** True when Send has something to send. */
    val canSend: Boolean get() = !sending && (draft.isNotBlank() || pictures.isNotEmpty())

    /** True when the draft can be scheduled: text only, to one person (an SMS). */
    val canSchedule: Boolean get() = canSend && pictures.isEmpty() && !isGroup

    /** True while the time chooser is open. */
    val choosingSchedule: Boolean get() = presets.isNotEmpty()
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

    /** Open the time chooser for the draft. */
    data object AskSchedule : ThreadEvent

    /** Schedule the draft for [atMillis]. */
    data class Schedule(
        val atMillis: Long,
    ) : ThreadEvent

    /** Close the time chooser. */
    data object CloseSchedule : ThreadEvent

    /** Cancel scheduled message [id]. */
    data class CancelScheduled(
        val id: Long,
    ) : ThreadEvent
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
        private val metadata: ThreadMetadata,
        dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val threadId = MutableStateFlow<Long?>(null)
        private var inFront = false
        private val header = MutableStateFlow(Header("", emptyList(), isGroup = false))
        private val composer = MutableStateFlow(ThreadUiState(loading = false))

        /** Screen state. */
        val uiState: StateFlow<ThreadUiState> =
            combine(threadId.filterNotNull().flatMapLatest(::messages), header, composer, sending.pending()) { loaded, head, local, later ->
                local.copy(
                    loading = loaded.loading,
                    canRead = loaded.canRead,
                    messages = loaded.messages,
                    title = head.title,
                    isGroup = head.isGroup,
                    failed = loaded.failed || local.failed,
                    scheduled = later.filter { head.recipients == listOf(it.to) },
                )
            }.flowOn(dispatchers.default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ThreadUiState())

        /** Handles [event]. */
        fun onEvent(event: ThreadEvent) {
            when (event) {
                is ThreadEvent.Open -> {
                    open(event)
                }

                is ThreadEvent.PickSim -> {
                    pickSim(event.subscriptionId)
                }

                ThreadEvent.Send -> {
                    send()
                }

                is ThreadEvent.Retry -> {
                    act { sending.retry(event.key) }
                }

                is ThreadEvent.Download -> {
                    act { sending.download(event.key.id) }
                }

                is ThreadEvent.Visible -> {
                    visible(event.visible)
                }

                ThreadEvent.AskSchedule -> {
                    composer.update {
                        it.copy(
                            presets = if (it.canSchedule) sending.schedulePresets() else emptyList(),
                        )
                    }
                }

                is ThreadEvent.Schedule -> {
                    schedule(event.atMillis)
                }

                is ThreadEvent.CancelScheduled -> {
                    act { sending.cancel(event.id) }
                }

                else -> {
                    composer.update { edited(it, event) }
                }
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
                ThreadEvent.CloseSchedule -> state.copy(presets = emptyList())
                else -> state
            }

        private fun open(event: ThreadEvent.Open) {
            val id = event.threadId
            if (threadId.value == id) return
            threadId.value = id
            if (inFront) visible(true)
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

        // The messages with a risky link or code (from OpenCaller's metadata) carry a warning (TC-SMS-005).
        private fun messages(id: Long): Flow<ThreadUiState> {
            if (!conversations.canRead()) return flowOf(ThreadUiState(loading = false, canRead = false))
            return combine(conversations.observeThread(id), metadata.observe()) { result, metas ->
                when (result) {
                    is AppResult.Success -> {
                        val risky = riskyMessages(metas, id)
                        ThreadUiState(
                            loading = false,
                            messages =
                                result.value.map {
                                    it.toItem(
                                        names,
                                        it.key.kind == MessageKind.SMS && it.key.id in risky,
                                    )
                                },
                        )
                    }

                    is AppResult.Failure -> {
                        ThreadUiState(loading = false, failed = true)
                    }
                }
            }
        }

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

        private fun schedule(atMillis: Long) {
            val state = composer.value
            val to = header.value.recipients.singleOrNull()
            composer.update { it.copy(presets = emptyList()) }
            if (to == null || !state.canSchedule) return
            viewModelScope.launch {
                val result = sending.schedule(to, state.draft.trim(), atMillis, state.simId)
                composer.update { if (result is AppResult.Success) it.copy(draft = "") else it.copy(failed = true) }
            }
        }

        private fun act(block: suspend () -> AppResult<Unit>) {
            viewModelScope.launch { if (block() is AppResult.Failure) composer.update { it.copy(failed = true) } }
        }

        // The screen can say it is in front before it says which thread it shows; the thread is read once both are known.
        private fun visible(visible: Boolean) {
            inFront = visible
            val id = threadId.value
            when {
                !visible -> presence.hidden()
                id != null -> viewModelScope.launch { presence.shown(id) }
            }
        }

        override fun onCleared() {
            presence.hidden()
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val MAX_PICTURES = 5
        }
    }

private suspend fun Message.toItem(
    names: ParticipantNames,
    risky: Boolean,
): ThreadItem =
    ThreadItem(
        key = key,
        outgoing = isOutgoing,
        sender = from?.let { names.title(it) },
        body = body,
        subject = subject,
        atMillis = atMillis,
        status = statusOf(this),
        images = attachments.filter { it.isImage }.map { it.uri },
        download = download,
        sizeBytes = sizeBytes,
        risky = risky,
    )

// Received SMS of thread [threadId] whose link was risky or whose code came during a call with a stranger.
private fun riskyMessages(
    metas: List<MessageMeta>,
    threadId: Long,
): Set<Long> {
    val risks = MessageRisk.LINK_RISKS + MessageRisk.OTP_DURING_CALL
    return metas
        .filter { it.threadId == threadId && MessageRisk.fromBits(it.riskFlags).any { risk -> risk in risks } }
        .mapTo(mutableSetOf()) { it.providerId }
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
