package io.github.jeshiomurmu.opencaller.feature.social

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.voip.AppCallAvailability
import io.github.jeshiomurmu.opencaller.core.data.voip.FlashRepository
import io.github.jeshiomurmu.opencaller.core.data.voip.Reachability
import io.github.jeshiomurmu.opencaller.core.model.FlashKind
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The outcome of the last Flash message sent from here, for a one-line message. */
sealed interface FlashOutcome {
    /** Sent. */
    data object Sent : FlashOutcome

    /** Not sent, with the reason's text. */
    data class Failed(
        @param:StringRes val reason: Int,
    ) : FlashOutcome
}

/** App call and Flash buttons for one number: what can be offered, and sending a Flash message. */
@HiltViewModel
class ReachViewModel
    @Inject
    constructor(
        availability: AppCallAvailability,
        private val flash: FlashRepository,
    ) : ViewModel() {
        /** What can be offered now. */
        val reachability: StateFlow<Reachability> =
            availability.reachability.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), Reachability(false, false))

        private val mutableOutcome = MutableStateFlow<FlashOutcome?>(null)

        /** The last Flash message's outcome. */
        val outcome: StateFlow<FlashOutcome?> = mutableOutcome.asStateFlow()

        /** Sends [kind] to [e164]. */
        fun sendFlash(
            e164: String,
            kind: FlashKind,
        ) {
            if (!PhoneNumber.isE164(e164)) return
            viewModelScope.launch {
                mutableOutcome.value =
                    when (val result = flash.send(PhoneNumber(e164), kind)) {
                        is AppResult.Success -> FlashOutcome.Sent
                        is AppResult.Failure -> FlashOutcome.Failed(result.error.message())
                    }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
