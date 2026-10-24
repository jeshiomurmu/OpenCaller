package io.github.jeshiomurmu.opencaller.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why a quick reply wasn't saved. */
enum class QuickReplyProblem {
    /** The text is empty. */
    EMPTY,

    /** The same reply is already in the list. */
    DUPLICATE,

    /** The list is full ([UserPreferences.MAX_QUICK_REPLIES]). */
    FULL,
}

/**
 * Settings → Quick replies.
 *
 * @property replies the user's replies; empty means Android's built-in ones are offered.
 * @property draft the text being written.
 * @property editing the index of the reply being changed, `null` while adding a new one.
 */
data class QuickRepliesUiState(
    val replies: List<String> = emptyList(),
    val draft: String = "",
    val editing: Int? = null,
    val problem: QuickReplyProblem? = null,
) {
    /** True when another reply can be added. */
    val canAdd: Boolean get() = editing != null || replies.size < UserPreferences.MAX_QUICK_REPLIES
}

/** What the user did on the quick replies screen. */
sealed interface QuickRepliesEvent {
    /** The text field changed (cut to [UserPreferences.MAX_QUICK_REPLY_LENGTH]). */
    data class Draft(
        val text: String,
    ) : QuickRepliesEvent

    /** Save the draft: as a new reply, or over the one being edited. */
    data object Save : QuickRepliesEvent

    /** Start changing reply [index]. */
    data class Edit(
        val index: Int,
    ) : QuickRepliesEvent

    /** Stop changing a reply without saving. */
    data object CancelEdit : QuickRepliesEvent

    /** Delete reply [index]. */
    data class Delete(
        val index: Int,
    ) : QuickRepliesEvent

    /** Move reply [index] one place up. */
    data class MoveUp(
        val index: Int,
    ) : QuickRepliesEvent
}

/**
 * The user's own quick replies for declining a call with a message and for the after-call card's Message (stored on
 * the device only). Without any, Android's built-in replies are used.
 */
@HiltViewModel
class QuickRepliesViewModel
    @Inject
    constructor(
        private val settings: UserSettingsDataSource,
    ) : ViewModel() {
        private val local = MutableStateFlow(QuickRepliesUiState())

        /** Screen state. */
        val uiState: StateFlow<QuickRepliesUiState> =
            combine(settings.preferences.map { it.quickReplies }, local) { replies, edit -> edit.copy(replies = replies) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), QuickRepliesUiState())

        /** Handles [event]. */
        fun onEvent(event: QuickRepliesEvent) {
            when (event) {
                is QuickRepliesEvent.Draft -> {
                    local.update {
                        it.copy(
                            draft = event.text.take(UserPreferences.MAX_QUICK_REPLY_LENGTH),
                            problem = null,
                        )
                    }
                }

                QuickRepliesEvent.Save -> {
                    save()
                }

                is QuickRepliesEvent.Edit -> {
                    uiState.value.replies.getOrNull(event.index)?.let { text ->
                        local.update { it.copy(draft = text, editing = event.index, problem = null) }
                    }
                }

                QuickRepliesEvent.CancelEdit -> {
                    local.update { QuickRepliesUiState() }
                }

                is QuickRepliesEvent.Delete -> {
                    reorder { replies -> replies.filterIndexed { i, _ -> i != event.index } }
                }

                is QuickRepliesEvent.MoveUp -> {
                    reorder { replies -> replies.movedUp(event.index) }
                }
            }
        }

        private fun save() {
            val state = uiState.value
            val text = state.draft.trim()
            val others = state.replies.filterIndexed { i, _ -> i != state.editing }
            val problem =
                when {
                    text.isEmpty() -> QuickReplyProblem.EMPTY
                    text in others -> QuickReplyProblem.DUPLICATE
                    state.editing == null && state.replies.size >= UserPreferences.MAX_QUICK_REPLIES -> QuickReplyProblem.FULL
                    else -> null
                }
            if (problem != null) {
                local.update { it.copy(problem = problem) }
                return
            }
            val editing = state.editing
            change { replies ->
                if (editing != null &&
                    editing in replies.indices
                ) {
                    replies.toMutableList().apply { set(editing, text) }
                } else {
                    replies + text
                }
            }
            local.update { QuickRepliesUiState() }
        }

        // Deleting or moving shifts the indices, so a reply being changed stops being edited.
        private fun reorder(transform: (List<String>) -> List<String>) {
            if (local.value.editing != null) local.update { QuickRepliesUiState() }
            change(transform)
        }

        private fun change(transform: (List<String>) -> List<String>) {
            viewModelScope.launch { settings.update { it.copy(quickReplies = transform(it.quickReplies)) } }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

private fun List<String>.movedUp(index: Int): List<String> {
    if (index !in 1 until size) return this
    return toMutableList().apply {
        val item = removeAt(index)
        add(index - 1, item)
    }
}
