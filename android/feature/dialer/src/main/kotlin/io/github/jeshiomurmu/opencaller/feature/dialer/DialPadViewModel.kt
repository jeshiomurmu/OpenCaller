package io.github.jeshiomurmu.opencaller.feature.dialer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.model.AppRole
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.telephony.AccessStatusSource
import io.github.jeshiomurmu.opencaller.core.telephony.CallPlacer
import io.github.jeshiomurmu.opencaller.core.telephony.DeviceCallPlacer
import io.github.jeshiomurmu.opencaller.core.telephony.NumberDisplay
import io.github.jeshiomurmu.opencaller.core.telephony.SimAccounts
import io.github.jeshiomurmu.opencaller.core.telephony.SimOption
import io.github.jeshiomurmu.opencaller.core.telephony.dialing.DialAction
import io.github.jeshiomurmu.opencaller.core.telephony.dialing.DialStrings
import io.github.jeshiomurmu.opencaller.feature.dialer.search.DialInput
import io.github.jeshiomurmu.opencaller.feature.dialer.search.DialSuggestions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** Why a call couldn't be placed. */
enum class DialProblem {
    /** The user refused the call permission. */
    NO_PERMISSION,

    /** The device can't place calls. */
    CANT_CALL,

    /** Android refused a secret code. */
    CODE_REFUSED,
}

/**
 * A suggestion under the number: a contact or a recent call matching what was typed (T9 or digits).
 *
 * @property display the number for display (national format at home, international otherwise).
 */
data class DialSuggestion(
    val name: String?,
    val dialString: String,
    val display: String,
)

/**
 * The dial pad.
 *
 * @property number what was typed; [display] is the same, formatted as typed.
 * @property isDefaultPhoneApp false: Call hands the number to the system phone app.
 * @property explainingPermission the call permission's explainer is showing (before the system dialog).
 * @property simOptions not `null` while the SIM picker shows (two SIMs and no default, TC-DIAL-003).
 */
data class DialPadUiState(
    val number: String = "",
    val display: String = "",
    val suggestions: List<DialSuggestion> = emptyList(),
    val isDefaultPhoneApp: Boolean = false,
    val explainingPermission: Boolean = false,
    val simOptions: List<SimOption>? = null,
    val problem: DialProblem? = null,
)

/** What the user did on the dial pad. */
sealed interface DialPadEvent {
    /** The dial pad opened with [number] (a `tel:` link or Call back); applied once. */
    data class Prefill(
        val number: String?,
    ) : DialPadEvent

    /** A key was pressed. */
    data class Key(
        val key: Char,
    ) : DialPadEvent

    /** A key was held: 1 calls voicemail, 0 types `+`. */
    data class LongPress(
        val key: Char,
    ) : DialPadEvent

    /** Text was pasted. */
    data class Paste(
        val text: String,
    ) : DialPadEvent

    /** Delete the last character. */
    data object Delete : DialPadEvent

    /** Delete everything. */
    data object Clear : DialPadEvent

    /** Call the number. */
    data object Call : DialPadEvent

    /** Call a suggestion. */
    data class CallSuggestion(
        val dialString: String,
    ) : DialPadEvent

    /** The SIM picker's choice. */
    data class ChooseSim(
        val simId: String,
    ) : DialPadEvent

    /** The SIM picker was closed without a choice. */
    data object DismissSims : DialPadEvent

    /** The explainer's Not now. */
    data object CancelPermission : DialPadEvent

    /** The system permission dialog answered. */
    data class PermissionResult(
        val granted: Boolean,
    ) : DialPadEvent

    /** The problem message was read. */
    data object DismissProblem : DialPadEvent
}

/** A call waiting for the call permission or a SIM. */
private sealed interface PendingCall {
    data class Dial(
        val dialString: String,
    ) : PendingCall

    data object Voicemail : PendingCall
}

/**
 * Number entry with T9 suggestions, and Call (docs/architecture.md §4.2, docs/prd.md F-CM-01). Emergency numbers are
 * placed at once: never a SIM picker or a suggestion in the way (TC-BLK-013).
 */
@HiltViewModel
class DialPadViewModel
    @Inject
    constructor(
        private val placer: CallPlacer,
        private val access: AccessStatusSource,
        private val sims: SimAccounts,
        private val numbers: NumberDisplay,
        suggestions: DialSuggestions,
        dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val entry = MutableStateFlow(DialPadUiState())
        private var prefilled = false
        private var pending: PendingCall? = null
        private val home: String by lazy { numbers.homeRegion() }

        /** Screen state. */
        val uiState: StateFlow<DialPadUiState> =
            combine(entry, access.status, suggestions.candidates().onStart { emit(emptyList()) }) { state, status, candidates ->
                state.copy(
                    display = numbers.asYouType(state.number, home),
                    suggestions =
                        suggestions
                            .search(
                                state.number,
                                candidates,
                            ).map { numbers.suggestion(it.candidate.name, it.candidate.dialString, home) },
                    isDefaultPhoneApp = status.roles.isHeld(AppRole.DIALER),
                )
            }.flowOn(dispatchers.default)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DialPadUiState())

        /** Handles [event]. */
        fun onEvent(event: DialPadEvent) {
            when (event) {
                is DialPadEvent.Prefill -> {
                    prefill(event.number)
                }

                is DialPadEvent.Key -> {
                    if (event.key in DialInput.ALLOWED) edit { it + event.key }
                }

                is DialPadEvent.LongPress -> {
                    longPress(event.key)
                }

                is DialPadEvent.Paste -> {
                    edit { it + DialInput.clean(event.text) }
                }

                DialPadEvent.Delete -> {
                    edit { it.dropLast(1) }
                }

                DialPadEvent.Clear -> {
                    edit { "" }
                }

                DialPadEvent.Call -> {
                    entry.value.number
                        .takeIf(String::isNotBlank)
                        ?.let { call(PendingCall.Dial(it)) }
                }

                is DialPadEvent.CallSuggestion -> {
                    call(PendingCall.Dial(event.dialString))
                }

                else -> {
                    onAnswer(event)
                }
            }
        }

        // The SIM picker, the permission explainer and the problem message.
        private fun onAnswer(event: DialPadEvent) {
            when (event) {
                is DialPadEvent.ChooseSim -> {
                    entry.update { it.copy(simOptions = null) }
                    pending?.let { place(it, event.simId) }
                    pending = null
                }

                DialPadEvent.DismissSims -> {
                    entry.update { it.copy(simOptions = null) }
                    pending = null
                }

                DialPadEvent.CancelPermission -> {
                    entry.update { it.copy(explainingPermission = false) }
                    pending = null
                }

                is DialPadEvent.PermissionResult -> {
                    permissionAnswered(event.granted)
                }

                else -> {
                    entry.update { it.copy(problem = null) }
                }
            }
        }

        private fun prefill(number: String?) {
            if (prefilled) return
            prefilled = true
            val cleaned = DialInput.clean(number.orEmpty())
            if (cleaned.isNotEmpty()) entry.update { it.copy(number = cleaned) }
        }

        private fun edit(change: (String) -> String) {
            entry.update { it.copy(number = change(it.number).take(DialInput.MAX_LENGTH), problem = null) }
        }

        // The key's own press already typed it; a long press replaces it (AOSP dialer behaviour).
        private fun longPress(key: Char) {
            val number = entry.value.number
            when {
                key == '1' && number == "1" -> {
                    edit { "" }
                    call(PendingCall.Voicemail)
                }

                key == '0' && number.endsWith('0') -> {
                    edit { it.dropLast(1) + '+' }
                }
            }
        }

        private fun call(call: PendingCall) {
            access.refresh()
            val dialString = (call as? PendingCall.Dial)?.dialString
            val action = dialString?.let(DialStrings::classify)
            when {
                !access.status.value.roles
                    .isHeld(AppRole.DIALER) -> {
                    dialString?.let { report(placer.handOff(it)) }
                }

                action is DialAction.SecretCode -> {
                    report(placer.sendSecretCode(action.digits))
                }

                // Emergency numbers: straight to Telecom, before any permission explainer or SIM picker (TC-BLK-013).
                dialString != null && placer.isEmergencyNumber(dialString) -> {
                    report(placer.placeEmergency(dialString))
                }

                !placer.canPlaceCalls() -> {
                    pending = call
                    entry.update { it.copy(explainingPermission = true) }
                }

                else -> {
                    chooseSimThenPlace(call)
                }
            }
        }

        private fun chooseSimThenPlace(call: PendingCall) {
            val choice = sims.current()
            if (choice.needsChoice) {
                pending = call
                entry.update { it.copy(simOptions = choice.options) }
            } else {
                place(call, simId = null)
            }
        }

        private fun place(
            call: PendingCall,
            simId: String?,
        ) {
            val result =
                when (call) {
                    is PendingCall.Dial -> placer.place(call.dialString, simId)
                    PendingCall.Voicemail -> placer.placeVoicemail(simId)
                }
            report(result)
            if (result is AppResult.Success && call is PendingCall.Dial) entry.update { it.copy(number = "") }
        }

        private fun permissionAnswered(granted: Boolean) {
            entry.update { it.copy(explainingPermission = false) }
            val waiting = pending
            pending = null
            when {
                !granted -> entry.update { it.copy(problem = DialProblem.NO_PERMISSION) }
                waiting != null -> call(waiting)
            }
        }

        private fun report(result: AppResult<Unit>) {
            val problem =
                when {
                    result is AppResult.Success -> null
                    (result as AppResult.Failure).error == AppError.Validation(DeviceCallPlacer.NO_PERMISSION) -> DialProblem.NO_PERMISSION
                    result.error == AppError.PermissionDenied -> DialProblem.CODE_REFUSED
                    else -> DialProblem.CANT_CALL
                }
            entry.update { it.copy(problem = problem) }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }

// A suggestion row: the number formatted for display when it is a phone number, else as stored.
private fun NumberDisplay.suggestion(
    name: String?,
    dialString: String,
    home: String,
): DialSuggestion {
    val display = dialString.takeIf(PhoneNumber::isE164)?.let { format(PhoneNumber(it), home) } ?: dialString
    return DialSuggestion(name, dialString, display)
}
