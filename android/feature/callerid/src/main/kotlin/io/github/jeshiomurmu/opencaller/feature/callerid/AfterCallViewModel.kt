package io.github.jeshiomurmu.opencaller.feature.callerid

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.telephony.QuickReplyTemplates
import io.github.jeshiomurmu.opencaller.core.ui.CallerCardState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What happened after Block or Not spam. */
enum class AfterCallDone {
    /** The number is blocked. */
    BLOCKED,

    /** The number is marked as not spam. */
    MARKED_NOT_SPAM,

    /** Saving the choice failed. */
    FAILED,
}

/**
 * The after-call screen.
 *
 * @property answered false for a missed or declined call.
 * @property flagged the caller was likely spam or suspicious: Not spam is offered instead of Report.
 * @property done the result of Block or Not spam, while it shows.
 * @property quickReplies the user's own quick replies, offered by Message (empty: Message opens an empty text).
 */
data class AfterCallUiState(
    val card: CallerCardModel? = null,
    val answered: Boolean = true,
    val done: AfterCallDone? = null,
    val working: Boolean = false,
    val quickReplies: List<String> = emptyList(),
) {
    /** True for a caller flagged as spam or suspicious. */
    val flagged: Boolean get() = card?.state == CallerCardState.SPAM || card?.state == CallerCardState.SUSPICIOUS
}

/** What the user did on the after-call screen (the other actions open other apps). */
sealed interface AfterCallEvent {
    /** Block the number. */
    data object Block : AfterCallEvent

    /** Mark the number as not spam. */
    data object NotSpam : AfterCallEvent
}

/**
 * The after-call screen of the default phone app: the caller card of the call that just ended, Block and Not spam.
 * The caller comes from [EndedCalls]; after the app was restarted only the number is known.
 */
@HiltViewModel
class AfterCallViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        ended: EndedCalls,
        formatter: CallerCardFormatter,
        private val actions: AfterCallActions,
        templates: QuickReplyTemplates,
    ) : ViewModel() {
        private val number: PhoneNumber? =
            savedState
                .get<String>(
                    AfterCallActivity.EXTRA_NUMBER,
                )?.takeIf(PhoneNumber::isE164)
                ?.let(::PhoneNumber)
        private val state =
            MutableStateFlow(
                AfterCallUiState(
                    card = number?.let { formatter.card(ended.take(it) ?: CallerInfo(it)) },
                    answered = savedState.get<Boolean>(AfterCallActivity.EXTRA_ANSWERED) ?: true,
                ),
            )

        /** Screen state. */
        val uiState: StateFlow<AfterCallUiState> = state.asStateFlow()

        init {
            viewModelScope.launch { templates.replies().collect { replies -> state.update { it.copy(quickReplies = replies) } } }
        }

        /** Handles [event]. */
        fun onEvent(event: AfterCallEvent) {
            val number = number ?: return
            if (state.value.working) return
            state.update { it.copy(working = true) }
            viewModelScope.launch {
                val result =
                    when (event) {
                        AfterCallEvent.Block -> actions.block(number)
                        AfterCallEvent.NotSpam -> actions.notSpam(number)
                    }
                val done =
                    when {
                        result is AppResult.Failure -> AfterCallDone.FAILED
                        event == AfterCallEvent.Block -> AfterCallDone.BLOCKED
                        else -> AfterCallDone.MARKED_NOT_SPAM
                    }
                state.update { it.copy(done = done, working = false) }
            }
        }
    }
