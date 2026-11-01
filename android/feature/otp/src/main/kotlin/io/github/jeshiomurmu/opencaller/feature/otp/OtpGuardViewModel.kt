package io.github.jeshiomurmu.opencaller.feature.otp

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallController
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStateRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The warning: who sent the code, whether it came as a flash call, and whether OpenCaller can end the call. */
data class OtpGuardUiState(
    val codeFrom: String? = null,
    val flashCall: Boolean = false,
    val canHangUp: Boolean = false,
)

/** What the user did on the warning. */
sealed interface OtpGuardEvent {
    /** End the call (only offered as the default phone app). */
    data object HangUp : OtpGuardEvent
}

/**
 * The OTP warning (TC-OTP-005). Hang up is offered while OpenCaller's in-call service has a live call, that is as
 * the default phone app; in screening-only mode only the system's call screen can end the call.
 */
@HiltViewModel
class OtpGuardViewModel
    @Inject
    constructor(
        savedState: SavedStateHandle,
        private val calls: CallStateRepository,
        private val controller: CallController,
    ) : ViewModel() {
        private val codeFrom: String? = savedState[OtpGuardActivity.EXTRA_FROM]
        private val flashCall: Boolean = savedState[OtpGuardActivity.EXTRA_FLASH_CALL] ?: false

        /** Screen state. */
        val uiState: StateFlow<OtpGuardUiState> =
            calls.state
                .map { state -> OtpGuardUiState(codeFrom, flashCall, canHangUp = state.topLevel.isNotEmpty()) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), OtpGuardUiState(codeFrom, flashCall))

        /** Handles [event]. */
        fun onEvent(event: OtpGuardEvent) {
            when (event) {
                OtpGuardEvent.HangUp -> {
                    calls.state.value.topLevel
                        .forEach { controller.hangUp(it.id) }
                }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
