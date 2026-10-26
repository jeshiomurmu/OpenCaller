package io.github.jeshiomurmu.opencaller.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.account.AccountStatus
import io.github.jeshiomurmu.opencaller.core.data.lookup.NumberSearch
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why a dispute wasn't sent. */
sealed interface DisputeProblem {
    /** The number isn't valid. */
    data object InvalidNumber : DisputeProblem

    /** The reason is empty or too long. */
    data object InvalidReason : DisputeProblem

    /** No connection. */
    data object Offline : DisputeProblem

    /** Too many disputes today. */
    data object TooMany : DisputeProblem

    /** The server wants a (new) sign-in. */
    data object SignInNeeded : DisputeProblem

    /** The server failed. */
    data object ServerError : DisputeProblem
}

/** Dispute screen state; [sentId] is set once the dispute is queued for review. */
data class DisputeUiState(
    val loading: Boolean = true,
    val serverConfigured: Boolean = false,
    val signedIn: Boolean = false,
    val busy: Boolean = false,
    val sentId: Long? = null,
    val problem: DisputeProblem? = null,
)

private data class DisputeLocal(
    val busy: Boolean = false,
    val sentId: Long? = null,
    val problem: DisputeProblem? = null,
)

/** "Wrongly marked as spam?": asks for a human review of a number's spam label (`POST /v1/disputes`). */
@HiltViewModel
class DisputeViewModel
    @Inject
    constructor(
        private val search: NumberSearch,
        private val account: AccountStatus,
        private val normalizer: PhoneNumberNormalizer,
        private val homeRegion: HomeRegionProvider,
    ) : ViewModel() {
        private val local = MutableStateFlow(DisputeLocal())

        /** Screen state. */
        val uiState: StateFlow<DisputeUiState> =
            combine(account.signedIn, local) { signedIn, state ->
                DisputeUiState(false, account.serverConfigured, signedIn, state.busy, state.sentId, state.problem)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DisputeUiState())

        /** Sends a dispute for [numberInput] with [reason]. */
        fun submit(
            numberInput: String,
            reason: String,
        ) {
            if (local.value.busy) return
            val text = reason.trim()
            val number = (normalizer.normalize(numberInput, homeRegion.homeRegion() ?: UNKNOWN_REGION) as? AppResult.Success)?.value
            val problem =
                when {
                    number == null -> DisputeProblem.InvalidNumber
                    text.isEmpty() || text.length > MAX_REASON -> DisputeProblem.InvalidReason
                    else -> null
                }
            if (number == null || problem != null) {
                local.value = DisputeLocal(problem = problem)
                return
            }
            local.value = DisputeLocal(busy = true)
            viewModelScope.launch {
                local.value =
                    when (val sent = search.dispute(number, text)) {
                        is AppResult.Success -> DisputeLocal(sentId = sent.value)
                        is AppResult.Failure -> DisputeLocal(problem = sent.error.toDisputeProblem())
                    }
            }
        }

        /** Limits. */
        companion object {
            /** Longest reason the server accepts. */
            const val MAX_REASON = 1_000
            private const val STOP_TIMEOUT_MILLIS = 5_000L
            private const val UNKNOWN_REGION = "ZZ"
        }
    }

/** Maps a dispute failure to what the screen shows. */
internal fun AppError.toDisputeProblem(): DisputeProblem =
    when (this) {
        AppError.Network, AppError.ServerNotConfigured -> DisputeProblem.Offline
        is AppError.RateLimited -> DisputeProblem.TooMany
        AppError.Auth -> DisputeProblem.SignInNeeded
        is AppError.Validation -> DisputeProblem.InvalidReason
        else -> DisputeProblem.ServerError
    }
