package io.github.jeshiomurmu.opencaller.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.account.AccountStatus
import io.github.jeshiomurmu.opencaller.core.data.lookup.NumberSearch
import io.github.jeshiomurmu.opencaller.core.data.lookup.SearchQuota
import io.github.jeshiomurmu.opencaller.core.data.lookup.SearchResult
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.SpamThreshold
import io.github.jeshiomurmu.opencaller.core.model.SpamVerdict
import io.github.jeshiomurmu.opencaller.core.model.VerdictSource
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Why a search didn't work (docs/design.md §8). */
sealed interface SearchProblem {
    /** The input isn't a valid phone number. */
    data object InvalidNumber : SearchProblem

    /** No connection. */
    data object Offline : SearchProblem

    /** Today's searches are used up. */
    data object QuotaUsed : SearchProblem

    /** The server wants a (new) sign-in. */
    data object SignInNeeded : SearchProblem

    /** The server failed. */
    data object ServerError : SearchProblem
}

/**
 * What a search found, ready for the caller card.
 *
 * @property notSpamCount people who said the number is not spam.
 */
data class SearchFound(
    val caller: CallerInfo,
    val formattedNumber: String,
    val notSpamCount: Int,
)

/** Search screen state. */
data class SearchUiState(
    val loading: Boolean = true,
    val serverConfigured: Boolean = false,
    val signedIn: Boolean = false,
    val quota: SearchQuota? = null,
    val busy: Boolean = false,
    val found: SearchFound? = null,
    val problem: SearchProblem? = null,
)

private data class SearchLocal(
    val busy: Boolean = false,
    val found: SearchFound? = null,
    val problem: SearchProblem? = null,
)

/** Explicit number search (signed in, 50 a day): the number is sent in full because the user typed it. */
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val search: NumberSearch,
        private val account: AccountStatus,
        private val settings: UserSettingsDataSource,
        private val normalizer: PhoneNumberNormalizer,
        private val homeRegion: HomeRegionProvider,
    ) : ViewModel() {
        private val local = MutableStateFlow(SearchLocal())

        /** Screen state. */
        val uiState: StateFlow<SearchUiState> =
            combine(account.signedIn, search.quota, local) { signedIn, quota, state ->
                SearchUiState(
                    loading = false,
                    serverConfigured = account.serverConfigured,
                    signedIn = signedIn,
                    quota = quota,
                    busy = state.busy,
                    found = state.found,
                    problem = state.problem,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SearchUiState())

        /** Searches for [input] (national or international format). */
        fun search(input: String) {
            if (local.value.busy) return
            val region = homeRegion.homeRegion() ?: UNKNOWN_REGION
            val number = (normalizer.normalize(input, region) as? AppResult.Success)?.value
            if (number == null) {
                local.value = SearchLocal(problem = SearchProblem.InvalidNumber)
                return
            }
            local.value = SearchLocal(busy = true)
            viewModelScope.launch {
                val threshold = settings.preferences.first().spamThreshold
                local.value =
                    when (val result = search.search(number)) {
                        is AppResult.Success -> SearchLocal(found = result.value.toFound(threshold))
                        is AppResult.Failure -> SearchLocal(problem = result.error.toSearchProblem())
                    }
            }
        }

        private fun SearchResult.toFound(threshold: SpamThreshold): SearchFound =
            SearchFound(
                caller =
                    CallerInfo(
                        number = number,
                        displayName = displayName,
                        nameSource = nameSource,
                        spamVerdict =
                            spamScore?.let { score ->
                                SpamVerdict(score, SpamVerdict.levelFor(score, threshold), category, reportCount, VerdictSource.SEARCH)
                            },
                        verifiedBusinessId = businessName?.let { VERIFIED },
                        regionCode = normalizer.regionCode(number),
                        lineType = normalizer.numberType(number),
                    ),
                formattedNumber = normalizer.format(number, homeRegion.homeRegion()),
                notSpamCount = notSpamCount,
            )

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val UNKNOWN_REGION = "ZZ"
            const val VERIFIED = "verified"
        }
    }

/** Maps a search failure to what the screen shows. */
internal fun AppError.toSearchProblem(): SearchProblem =
    when (this) {
        AppError.Network, AppError.ServerNotConfigured -> SearchProblem.Offline
        is AppError.RateLimited -> SearchProblem.QuotaUsed
        AppError.Auth -> SearchProblem.SignInNeeded
        is AppError.Validation -> SearchProblem.InvalidNumber
        else -> SearchProblem.ServerError
    }
