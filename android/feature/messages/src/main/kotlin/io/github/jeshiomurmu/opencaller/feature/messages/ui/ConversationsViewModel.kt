package io.github.jeshiomurmu.opencaller.feature.messages.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.model.Conversation
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.ConversationSource
import io.github.jeshiomurmu.opencaller.feature.messages.notify.ParticipantNames
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One conversation in the list. [title] is the people's names (or numbers), comma-separated for a group. */
data class ConversationRow(
    val threadId: Long,
    val title: String,
    val snippet: String,
    val atMillis: Long,
    val unread: Int,
    val isGroup: Boolean,
    val fromMe: Boolean,
    val hasImage: Boolean,
)

/** The conversations list. */
data class ConversationsUiState(
    val loading: Boolean = true,
    val canRead: Boolean = true,
    val rows: List<ConversationRow> = emptyList(),
    val confirmDelete: ConversationRow? = null,
    val failed: Boolean = false,
)

/** What the user did on the list. */
sealed interface ConversationsEvent {
    /** The screen came back: read again (the role or permissions may have changed). */
    data object Reload : ConversationsEvent

    /** Ask before deleting [row]. */
    data class AskDelete(
        val row: ConversationRow,
    ) : ConversationsEvent

    /** Delete the conversation asked about. */
    data object ConfirmDelete : ConversationsEvent

    /** Keep it. */
    data object CancelDelete : ConversationsEvent

    /** The failure was read. */
    data object DismissFailure : ConversationsEvent
}

/** Forgets what OpenCaller kept about a deleted conversation's messages (their MessageMeta rows). */
fun interface ThreadMetaCleaner {
    /** Forgets thread [threadId]. */
    suspend fun forget(threadId: Long)
}

private data class Controls(
    val confirmDelete: ConversationRow? = null,
    val failed: Boolean = false,
)

/** The conversations of the default SMS app, newest first, named from the user's contacts on the device. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationsViewModel
    @Inject
    constructor(
        private val conversations: ConversationSource,
        private val names: ParticipantNames,
        private val meta: ThreadMetaCleaner,
        dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val reload = MutableStateFlow(0)
        private val controls = MutableStateFlow(Controls())

        /** Screen state. */
        val uiState: StateFlow<ConversationsUiState> =
            combine(reload.flatMapLatest { rows() }, controls) { loaded, local ->
                loaded.copy(confirmDelete = local.confirmDelete, failed = loaded.failed || local.failed)
            }.flowOn(dispatchers.default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ConversationsUiState())

        /** Handles [event]. */
        fun onEvent(event: ConversationsEvent) {
            when (event) {
                ConversationsEvent.Reload -> reload.update { it + 1 }
                is ConversationsEvent.AskDelete -> controls.update { it.copy(confirmDelete = event.row) }
                ConversationsEvent.ConfirmDelete -> delete()
                ConversationsEvent.CancelDelete -> controls.update { it.copy(confirmDelete = null) }
                ConversationsEvent.DismissFailure -> controls.update { it.copy(failed = false) }
            }
        }

        private fun rows(): Flow<ConversationsUiState> {
            if (!conversations.canRead()) return flowOf(ConversationsUiState(loading = false, canRead = false))
            return conversations.observeConversations().map { result ->
                when (result) {
                    is AppResult.Success -> ConversationsUiState(loading = false, rows = result.value.map { rowOf(it) })
                    is AppResult.Failure -> ConversationsUiState(loading = false, failed = true)
                }
            }
        }

        private suspend fun rowOf(conversation: Conversation): ConversationRow =
            ConversationRow(
                threadId = conversation.threadId,
                title = conversation.participants.map { names.title(it) }.joinToString(", "),
                snippet = conversation.snippet,
                atMillis = conversation.lastAtMillis,
                unread = conversation.unreadCount,
                isGroup = conversation.isGroup,
                fromMe = conversation.lastFromMe,
                hasImage = conversation.lastHasImage,
            )

        private fun delete() {
            val row = controls.value.confirmDelete ?: return
            controls.update { it.copy(confirmDelete = null) }
            viewModelScope.launch {
                when (conversations.delete(row.threadId)) {
                    is AppResult.Success -> meta.forget(row.threadId)
                    is AppResult.Failure -> controls.update { it.copy(failed = true) }
                }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
