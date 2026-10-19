package io.github.jeshiomurmu.opencaller.feature.search

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.account.AccountStatus
import io.github.jeshiomurmu.opencaller.core.data.lookup.NumberSearch
import io.github.jeshiomurmu.opencaller.core.data.lookup.SearchQuota
import io.github.jeshiomurmu.opencaller.core.data.lookup.SearchResult
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** Search that answers with whatever the test set and counts the quota like the real one. */
class FakeNumberSearch : NumberSearch {
    val quotaState = MutableStateFlow(SearchQuota(limit = 50, remaining = 50, blockedUntil = null))
    override val quota: Flow<SearchQuota> = quotaState
    var searchResult: (PhoneNumber) -> AppResult<SearchResult> = { AppResult.Success(empty(it)) }
    var disputeResult: AppResult<Long> = AppResult.Success(7)
    val searched = mutableListOf<PhoneNumber>()
    val disputes = mutableListOf<Pair<PhoneNumber, String>>()

    override suspend fun search(number: PhoneNumber): AppResult<SearchResult> {
        searched += number
        return searchResult(number).also {
            if (it is AppResult.Success) quotaState.value = quotaState.value.copy(remaining = quotaState.value.remaining - 1)
        }
    }

    override suspend fun dispute(
        number: PhoneNumber,
        reason: String,
    ): AppResult<Long> = disputeResult.also { disputes += number to reason }

    companion object {
        fun empty(number: PhoneNumber) = SearchResult(number, null, null, null, null, null, 0, 0, null)
    }
}

/** Sign-in status the test can change. */
class FakeAccountStatus(
    override val serverConfigured: Boolean = true,
) : AccountStatus {
    val signedInState = MutableStateFlow(true)
    override val signedIn: Flow<Boolean> = signedInState
    override val unlistPageUrl: String? = null
}
