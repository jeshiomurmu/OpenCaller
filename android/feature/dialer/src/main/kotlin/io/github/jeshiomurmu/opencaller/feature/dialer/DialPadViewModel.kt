package io.github.jeshiomurmu.opencaller.feature.dialer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.AppRole
import io.github.jeshiomurmu.opencaller.core.telephony.AccessStatusSource
import io.github.jeshiomurmu.opencaller.core.telephony.CallPlacer
import io.github.jeshiomurmu.opencaller.core.telephony.DeviceCallPlacer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/** Why a call couldn't be placed. */
enum class DialProblem {
    /** The user refused the call permission. */
    NO_PERMISSION,

    /** The device can't place calls. */
    CANT_CALL,
}

/**
 * The dial pad.
 *
 * @property isDefaultPhoneApp false: Call hands the number to the system phone app.
 * @property explainingPermission the call permission's explainer is showing (before the system dialog).
 */
data class DialPadUiState(
    val number: String = "",
    val isDefaultPhoneApp: Boolean = false,
    val explainingPermission: Boolean = false,
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

    /** Delete the last character. */
    data object Delete : DialPadEvent

    /** Delete everything. */
    data object Clear : DialPadEvent

    /** Call the number. */
    data object Call : DialPadEvent

    /** The explainer's Not now. */
    data object CancelPermission : DialPadEvent

    /** The system permission dialog answered. */
    data class PermissionResult(
        val granted: Boolean,
    ) : DialPadEvent

    /** The problem message was read. */
    data object DismissProblem : DialPadEvent
}

/** Number entry and Call (docs/architecture.md §4.2). */
@HiltViewModel
class DialPadViewModel
    @Inject
    constructor(
        private val placer: CallPlacer,
        private val access: AccessStatusSource,
    ) : ViewModel() {
        private val entry = MutableStateFlow(DialPadUiState())
        private var prefilled = false

        /** Screen state. */
        val uiState: StateFlow<DialPadUiState> =
            combine(entry, access.status) { state, status -> state.copy(isDefaultPhoneApp = status.roles.isHeld(AppRole.DIALER)) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DialPadUiState())

        /** Handles [event]. */
        fun onEvent(event: DialPadEvent) {
            when (event) {
                is DialPadEvent.Prefill -> prefill(event.number)
                is DialPadEvent.Key -> if (event.key in ALLOWED) edit { it + event.key }
                DialPadEvent.Delete -> edit { it.dropLast(1) }
                DialPadEvent.Clear -> edit { "" }
                DialPadEvent.Call -> call()
                DialPadEvent.CancelPermission -> entry.update { it.copy(explainingPermission = false) }
                is DialPadEvent.PermissionResult -> permissionAnswered(event.granted)
                DialPadEvent.DismissProblem -> entry.update { it.copy(problem = null) }
            }
        }

        private fun prefill(number: String?) {
            if (prefilled) return
            prefilled = true
            val cleaned = number.orEmpty().filter { it in ALLOWED }.take(MAX_LENGTH)
            if (cleaned.isNotEmpty()) entry.update { it.copy(number = cleaned) }
        }

        private fun edit(change: (String) -> String) {
            entry.update { it.copy(number = change(it.number).take(MAX_LENGTH), problem = null) }
        }

        private fun call() {
            val number = entry.value.number.takeIf(String::isNotBlank) ?: return
            access.refresh()
            when {
                !access.status.value.roles
                    .isHeld(AppRole.DIALER) -> report(placer.handOff(number))

                !placer.canPlaceCalls() -> entry.update { it.copy(explainingPermission = true) }

                else -> report(placer.place(number))
            }
        }

        private fun permissionAnswered(granted: Boolean) {
            entry.update { it.copy(explainingPermission = false) }
            if (granted) call() else entry.update { it.copy(problem = DialProblem.NO_PERMISSION) }
        }

        private fun report(result: AppResult<Unit>) {
            val problem =
                when {
                    result is AppResult.Success -> null
                    (result as AppResult.Failure).error == AppError.Validation(DeviceCallPlacer.NO_PERMISSION) -> DialProblem.NO_PERMISSION
                    else -> DialProblem.CANT_CALL
                }
            entry.update { it.copy(problem = problem) }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L

            // Digits, the keypad's * and #, + for international numbers, and pauses (, ;).
            const val ALLOWED = "0123456789*#+,;"
            const val MAX_LENGTH = 64
        }
    }
