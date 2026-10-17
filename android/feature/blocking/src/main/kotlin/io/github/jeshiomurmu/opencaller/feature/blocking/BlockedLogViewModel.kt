package io.github.jeshiomurmu.opencaller.feature.blocking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.data.ScreeningEventRepository
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.model.SpamVerdict
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import io.github.jeshiomurmu.opencaller.core.ui.ReportDraft
import io.github.jeshiomurmu.opencaller.core.ui.WhyReason
import io.github.jeshiomurmu.opencaller.core.ui.whyReasons
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
 * @property spamScore the spam score the decision used, if any.
 */
data class BlockedCall(
    val eventId: Long,
    val number: PhoneNumber?,
    val display: String?,
    val decision: Decision,
    val reason: ScreeningReason,
    val atMillis: Long,
    val spamScore: Float? = null,
) {
    /** "Why am I seeing this?" for this call. */
    val why: List<WhyReason>
        get() =
            whyReasons(
                CallerInfo(number, spamVerdict = spamScore?.let { SpamVerdict(it.coerceIn(0f, 1f), RiskLevel.LIKELY_SPAM, null, 0) }),
                reason,
            )
}

/** Short messages shown once, as a snackbar. */
sealed interface BlockedLogMessage {
    /** The number will ring next time. */
    data class Unblocked(
        val masked: String,
    ) : BlockedLogMessage

    /** The report was queued (and the number blocked when [blocked]). */
    data class Reported(
        val masked: String,
        val blocked: Boolean,
    ) : BlockedLogMessage

    /** Saving failed. */
    data object Failed : BlockedLogMessage
}

/**
 * Blocked-calls log.
 *
 * @property reporting the call whose report sheet is open.
 * @property explaining the call whose "Why?" sheet is open.
 */
data class BlockedLogUiState(
    val loading: Boolean = true,
    val calls: List<BlockedCall> = emptyList(),
    val message: BlockedLogMessage? = null,
    val reporting: BlockedCall? = null,
    val explaining: BlockedCall? = null,
    val signedIn: Boolean = false,
)

/** User actions on the blocked-calls log. */
sealed interface BlockedLogEvent {
    /** Undo the block of [call]. */
    data class Unblock(
        val call: BlockedCall,
    ) : BlockedLogEvent

    /** Open the report sheet for [call]. */
    data class Report(
        val call: BlockedCall,
    ) : BlockedLogEvent

    /** Send the open report. */
    data class SubmitReport(
        val draft: ReportDraft,
    ) : BlockedLogEvent

    /** Open "Why?" for [call]. */
    data class Explain(
        val call: BlockedCall,
    ) : BlockedLogEvent

    /** Close the open sheet. */
    data object DismissSheet : BlockedLogEvent

    /** The snackbar was shown. */
    data object MessageShown : BlockedLogEvent
}

private data class Sheets(
    val message: BlockedLogMessage? = null,
    val reporting: BlockedCall? = null,
    val explaining: BlockedCall? = null,
)

/**
 * The blocked-calls log (from the screening log), with Undo per call (docs/design.md §7: blocking is reversible),
 * Report and "Why?".
 */
@HiltViewModel
class BlockedLogViewModel
    @Inject
    constructor(
        events: ScreeningEventRepository,
        private val unblockNumber: UnblockNumber,
        private val normalizer: PhoneNumberNormalizer,
        private val homeRegion: HomeRegionProvider,
        private val reportNumber: ReportNumber,
    ) : ViewModel() {
        private val sheets = MutableStateFlow(Sheets())

        /** Screen state. */
        val uiState: StateFlow<BlockedLogUiState> =
            combine(events.observeBlocked(LOG_SIZE), sheets, reportNumber.signedIn) { blocked, open, signedIn ->
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
                                event.spamScore,
                            )
                        },
                    message = open.message,
                    reporting = open.reporting,
                    explaining = open.explaining,
                    signedIn = signedIn,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BlockedLogUiState())

        /** Handles [event]. */
        fun onEvent(event: BlockedLogEvent) {
            when (event) {
                is BlockedLogEvent.Unblock -> unblockCall(event.call)
                is BlockedLogEvent.Report -> sheets.value = Sheets(reporting = event.call.takeIf { it.number != null })
                is BlockedLogEvent.SubmitReport -> submitReport(event.draft)
                is BlockedLogEvent.Explain -> sheets.value = Sheets(explaining = event.call)
                BlockedLogEvent.DismissSheet -> sheets.value = sheets.value.copy(reporting = null, explaining = null)
                BlockedLogEvent.MessageShown -> sheets.value = sheets.value.copy(message = null)
            }
        }

        private fun submitReport(draft: ReportDraft) {
            val number = sheets.value.reporting?.number ?: return
            sheets.value = Sheets()
            viewModelScope.launch {
                val message =
                    when (val saved = reportNumber(number, draft)) {
                        is AppResult.Success -> BlockedLogMessage.Reported(normalizer.masked(number), saved.value)
                        is AppResult.Failure -> BlockedLogMessage.Failed
                    }
                sheets.value = sheets.value.copy(message = message)
            }
        }

        private fun unblockCall(call: BlockedCall) {
            val number = call.number ?: return
            viewModelScope.launch {
                val message =
                    when (unblockNumber(number)) {
                        is AppResult.Success -> BlockedLogMessage.Unblocked(normalizer.masked(number))
                        is AppResult.Failure -> BlockedLogMessage.Failed
                    }
                sheets.value = sheets.value.copy(message = message)
            }
        }

        private companion object {
            const val LOG_SIZE = 100
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
