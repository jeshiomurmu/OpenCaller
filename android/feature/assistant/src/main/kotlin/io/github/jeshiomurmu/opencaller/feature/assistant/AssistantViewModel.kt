package io.github.jeshiomurmu.opencaller.feature.assistant

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantAvailability
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantOffer
import io.github.jeshiomurmu.opencaller.core.data.assistant.CallAssistant
import io.github.jeshiomurmu.opencaller.core.model.AssistantLine
import io.github.jeshiomurmu.opencaller.core.model.AssistantUsage
import io.github.jeshiomurmu.opencaller.core.model.ScreenedCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The assistant's setup screen: the number, the codes to dial, this month's minutes and the history. */
data class AssistantUiState(
    val offer: AssistantOffer? = null,
    val line: AssistantLine? = null,
    val codes: List<ForwardingCode> = emptyList(),
    val usage: AssistantUsage? = null,
    val history: List<ScreenedCall> = emptyList(),
    val busy: Boolean = false,
    @param:StringRes val message: Int? = null,
)

/** What the user does on the setup screen. */
sealed interface AssistantEvent {
    /** Load the number and the minutes. */
    data object Refresh : AssistantEvent

    /** Get an assistant number. */
    data object TurnOn : AssistantEvent

    /** Give the number back (the screen first asks the user to dial the cancel code). */
    data object TurnOff : AssistantEvent

    /** Delete screened call [id] from the phone. */
    data class Delete(
        val id: String,
    ) : AssistantEvent

    /** The message was shown. */
    data object MessageShown : AssistantEvent
}

/** The setup, the minutes meter and the history (F-UT-04). */
@HiltViewModel
class AssistantViewModel
    @Inject
    constructor(
        private val assistant: CallAssistant,
        availability: AssistantAvailability,
    ) : ViewModel() {
        private val local = MutableStateFlow(AssistantUiState())

        /** Screen state. */
        val uiState: StateFlow<AssistantUiState> =
            combine(local, availability.offer(), assistant.history) { state, offer, history ->
                state.copy(offer = offer, history = history)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AssistantUiState())

        /** Handles [event]. */
        fun onEvent(event: AssistantEvent) {
            when (event) {
                AssistantEvent.Refresh -> refresh()
                AssistantEvent.TurnOn -> turnOn()
                AssistantEvent.TurnOff -> turnOff()
                is AssistantEvent.Delete -> viewModelScope.launch { assistant.delete(event.id) }
                AssistantEvent.MessageShown -> local.update { it.copy(message = null) }
            }
        }

        private fun refresh() =
            busy {
                val line = assistant.line()
                val usage = assistant.usage()
                local.update {
                    it.copy(
                        line = (line as? AppResult.Success)?.value,
                        codes = (line as? AppResult.Success)?.value?.let { found -> ForwardingCodes.forNumber(found.number) }.orEmpty(),
                        usage = (usage as? AppResult.Success)?.value,
                        message = (line as? AppResult.Failure)?.error?.let(::messageOf),
                    )
                }
            }

        private fun turnOn() =
            busy {
                when (val result = assistant.allocate()) {
                    is AppResult.Success -> {
                        local.update { it.copy(line = result.value, codes = ForwardingCodes.forNumber(result.value.number)) }
                    }

                    is AppResult.Failure -> {
                        local.update { it.copy(message = messageOf(result.error)) }
                    }
                }
            }

        private fun turnOff() =
            busy {
                when (val result = assistant.release()) {
                    is AppResult.Success -> local.update { it.copy(line = null, codes = emptyList()) }
                    is AppResult.Failure -> local.update { it.copy(message = messageOf(result.error)) }
                }
            }

        private fun busy(block: suspend () -> Unit) {
            viewModelScope.launch {
                local.update { it.copy(busy = true) }
                try {
                    block()
                } finally {
                    local.update { it.copy(busy = false) }
                }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val SUPPORTER_REQUIRED = "supporter_required"

            fun messageOf(error: AppError): Int =
                when (error) {
                    AppError.Auth, AppError.ServerNotConfigured -> {
                        R.string.assistant_signed_out
                    }

                    is AppError.Validation -> {
                        if (error.message ==
                            SUPPORTER_REQUIRED
                        ) {
                            R.string.assistant_supporter
                        } else {
                            R.string.assistant_unavailable
                        }
                    }

                    else -> {
                        R.string.assistant_network_error
                    }
                }
        }
    }
