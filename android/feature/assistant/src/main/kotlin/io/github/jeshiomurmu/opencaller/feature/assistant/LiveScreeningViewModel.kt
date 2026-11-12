package io.github.jeshiomurmu.opencaller.feature.assistant

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantAction
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantEvent
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantMessages
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantRoomClient
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantRoomSession
import io.github.jeshiomurmu.opencaller.core.data.assistant.CallAssistant
import io.github.jeshiomurmu.opencaller.core.model.AssistantCallRoom
import io.github.jeshiomurmu.opencaller.core.model.CallerDetails
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreenedCall
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEnd
import io.github.jeshiomurmu.opencaller.core.model.TranscriptLine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Optional
import javax.inject.Inject

/** Where the live screen is. */
enum class LivePhase {
    CONNECTING,
    LIVE,
    ENDED,
    FAILED,
    NOT_IN_BUILD,
}

/** The live screen: who calls, what was said so far, what the assistant learned. */
data class LiveUiState(
    val phase: LivePhase = LivePhase.CONNECTING,
    val caller: PhoneNumber? = null,
    val callerHidden: Boolean = false,
    val lines: List<TranscriptLine> = emptyList(),
    val partial: String? = null,
    val details: CallerDetails? = null,
    val end: ScreeningEnd? = null,
    @param:StringRes val message: Int? = null,
    /** Set once the user chose Call back: the screen opens the dialer with this number, then clears it. */
    val dial: PhoneNumber? = null,
)

/** What the user does on the live screen. */
sealed interface LiveEvent {
    /** Say [text] to the caller. */
    data class Reply(
        val text: String,
    ) : LiveEvent

    /** The assistant tells the caller the user will call back; the dialer opens with the caller's number. */
    data object CallBack : LiveEvent

    /** The assistant ends the call; it's kept as spam in the history. */
    data object Spam : LiveEvent

    /** The dialer was opened. */
    data object Dialled : LiveEvent

    /** The message was shown. */
    data object MessageShown : LiveEvent
}

/**
 * Follows call `callId` (the notification's extra) in its LiveKit room (ADR 0010). Everything the agent sends is kept
 * on this phone only ([CallAssistant.keep]), never sent to the server.
 */
@HiltViewModel
class LiveScreeningViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val assistant: CallAssistant,
        private val rooms: Optional<AssistantRoomClient>,
    ) : ViewModel() {
        private val callId: String = savedState.get<String>(EXTRA_CALL_ID).orEmpty()
        private val state = MutableStateFlow(LiveUiState())
        private var session: AssistantRoomSession? = null
        private var startedAtMillis = 0L

        /** Screen state. */
        val uiState: StateFlow<LiveUiState> = state.asStateFlow()

        init {
            viewModelScope.launch { follow() }
        }

        /** Handles [event]. */
        fun onEvent(event: LiveEvent) {
            when (event) {
                is LiveEvent.Reply -> {
                    event.text
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { send(AssistantAction.Reply(it)) }
                }

                LiveEvent.CallBack -> {
                    callBack()
                }

                LiveEvent.Spam -> {
                    send(AssistantAction.Spam)
                }

                LiveEvent.Dialled -> {
                    state.update { it.copy(dial = null) }
                }

                LiveEvent.MessageShown -> {
                    state.update { it.copy(message = null) }
                }
            }
        }

        private suspend fun follow() {
            val client = rooms.orElse(null)
            val access = (assistant.room(callId) as? AppResult.Success)?.value
            val live = client?.let { join(it, access) }
            state.update { it.copy(phase = phaseOf(client != null, access, live)) }
            if (live == null) return
            session = live
            live.messages.collect { payload -> AssistantMessages.parse(payload)?.let { apply(it) } }
            if (state.value.phase == LivePhase.LIVE) state.update { it.copy(phase = LivePhase.ENDED) }
            keep()
        }

        // The room while the call lasts; null when it ended, can't be fetched or can't be joined.
        private suspend fun join(
            client: AssistantRoomClient,
            access: AssistantCallRoom?,
        ): AssistantRoomSession? {
            val url = access?.url?.takeUnless { access.ended }
            val token = access?.token
            if (url == null || token == null) return null
            startedAtMillis = access.startedAtMillis
            return (client.connect(url, token) as? AppResult.Success)?.value
        }

        private fun phaseOf(
            buildHasLive: Boolean,
            access: AssistantCallRoom?,
            live: AssistantRoomSession?,
        ): LivePhase =
            when {
                !buildHasLive -> LivePhase.NOT_IN_BUILD
                live != null -> LivePhase.LIVE
                access?.ended == true -> LivePhase.ENDED
                else -> LivePhase.FAILED
            }

        private suspend fun apply(event: AssistantEvent) {
            state.update { current ->
                when (event) {
                    is AssistantEvent.Hello -> current.copy(caller = event.caller, callerHidden = event.caller == null)
                    is AssistantEvent.Partial -> current.copy(partial = event.text)
                    is AssistantEvent.Line -> current.copy(lines = current.lines + event.line, partial = null)
                    is AssistantEvent.Details -> current.copy(details = event.details)
                    is AssistantEvent.Ended -> current.copy(phase = LivePhase.ENDED, end = event.end, partial = null)
                }
            }
            // Lines and details are what the history keeps; a partial line isn't.
            if (event !is AssistantEvent.Partial) keep()
            if (event is AssistantEvent.Ended) session?.close()
        }

        private suspend fun keep() {
            val current = state.value
            val heardSomething = current.lines.isNotEmpty() || current.caller != null || current.callerHidden
            if (callId.isEmpty() || !heardSomething) return
            assistant.keep(
                ScreenedCall(
                    id = callId,
                    startedAtMillis = startedAtMillis,
                    caller = current.caller,
                    details = current.details ?: CallerDetails(),
                    transcript = current.lines,
                    end = current.end ?: ScreeningEnd.UNKNOWN,
                ),
            )
        }

        private fun callBack() {
            send(AssistantAction.CallBack)
            state.value.caller?.let { number -> state.update { it.copy(dial = number) } }
        }

        private fun send(action: AssistantAction) {
            val live = session
            if (live == null || state.value.phase != LivePhase.LIVE) {
                state.update { it.copy(message = R.string.assistant_live_not_connected) }
                return
            }
            viewModelScope.launch {
                if (live.send(AssistantMessages.encode(action)) is AppResult.Failure) {
                    state.update { it.copy(message = R.string.assistant_live_send_failed) }
                }
            }
        }

        // The screen may close mid-call; leaving the room doesn't end the call (the agent goes on).
        override fun onCleared() {
            session?.close()
            session = null
        }

        /** The notification's extra. */
        companion object {
            /** The call's id (`assistant_call` push item). */
            const val EXTRA_CALL_ID = "io.github.jeshiomurmu.opencaller.extra.ASSISTANT_CALL_ID"
        }
    }
