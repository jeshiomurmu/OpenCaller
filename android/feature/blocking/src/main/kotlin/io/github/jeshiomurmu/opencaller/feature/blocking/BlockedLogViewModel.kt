package io.github.jeshiomurmu.opencaller.feature.blocking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.data.ScreeningEventRepository
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One blocked or silenced call in the log.
 *
 * @property number the caller, or `null` for a hidden number (which can't be unblocked one by one).
 */
data class BlockedCall(
    val eventId: Long,
    val number: PhoneNumber?,
    val display: String?,
    val decision: Decision,
    val reason: ScreeningReason,
    val atMillis: Long,
)

/** Short messages shown once, as a snackbar. */
sealed interface BlockedLogMessage {
    /** The number will ring next time. */
    data class Unblocked(
        val masked: String,
    ) : BlockedLogMessage

    /** Unblocking failed. */
    data object Failed : BlockedLogMessage
}

/** Blocked-calls log. */
data class BlockedLogUiState(
    val loading: Boolean = true,
    val calls: List<BlockedCall> = emptyList(),
    val message: BlockedLogMessage? = null,
)

/** User actions on the blocked-calls log. */
sealed interface BlockedLogEvent {
    /** Undo the block of [call]. */
    data class Unblock(
        val call: BlockedCall,
    ) : BlockedLogEvent

    /** The snackbar was shown. */
    data object MessageShown : BlockedLogEvent
}

/** The blocked-calls log (from the screening log), with Undo per call (docs/design.md §7: blocking is reversible). */
@HiltViewModel
class BlockedLogViewModel
    @Inject
    constructor(
        events: ScreeningEventRepository,
        private val unblockNumber: UnblockNumber,
        private val normalizer: PhoneNumberNormalizer,
        private val homeRegion: HomeRegionProvider,
    ) : ViewModel() {
        private val message = MutableStateFlow<BlockedLogMessage?>(null)

        /** Screen state. */
        val uiState: StateFlow<BlockedLogUiState> =
            combine(events.observeBlocked(LOG_SIZE), message) { blocked, shown ->
                val region = homeRegion.homeRegion()
                BlockedLogUiState(
                    loading = false,
                    calls =
                        blocked.getOrNull().orEmpty().map { event ->
                            BlockedCall(
                                event.id,
                                event.number,
                                event.number?.let { normalizer.format(it, region) },
                                event.decision,
                                event.reason,
                                event.atMillis,
                            )
                        },
                    message = shown,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BlockedLogUiState())

        /** Handles [event]. */
        fun onEvent(event: BlockedLogEvent) {
            when (event) {
                is BlockedLogEvent.Unblock -> unblockCall(event.call)
                BlockedLogEvent.MessageShown -> message.value = null
            }
        }

        private fun unblockCall(call: BlockedCall) {
            val number = call.number ?: return
            viewModelScope.launch {
                message.value =
                    when (unblockNumber(number)) {
                        is AppResult.Success -> BlockedLogMessage.Unblocked(normalizer.masked(number))
                        is AppResult.Failure -> BlockedLogMessage.Failed
                    }
            }
        }

        private companion object {
            const val LOG_SIZE = 100
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
