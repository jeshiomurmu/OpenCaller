package io.github.jeshiomurmu.opencaller.core.data.lookup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.network.DisputeRequest
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerApi
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Explicit number search (docs/prd.md F-ID-03): the user typed the number, so it is sent in full (unlike the live
 * lookup). Needs sign-in; 50 a day.
 */
@Singleton
class SearchRepository
    @Inject
    constructor(
        private val api: OpenCallerApi,
        private val quotaStore: SearchQuotaStore,
        private val clock: Clock,
    ) : NumberSearch {
        override val quota: Flow<SearchQuota> = quotaStore.quota { clock.nowMillis() }

        override suspend fun search(number: PhoneNumber): AppResult<SearchResult> {
            val result = apiCall { api.search(number.e164) }
            val now = clock.nowMillis()
            when (result) {
                is AppResult.Success -> {
                    quotaStore.recordSearch(now)
                }

                is AppResult.Failure -> {
                    val limited = result.error as? AppError.RateLimited
                    if (limited != null) quotaStore.blockUntil(now + (limited.retryAfterSeconds ?: DAY_SECONDS) * MILLIS)
                }
            }
            return when (result) {
                is AppResult.Success -> AppResult.Success(result.value.info.toSearchResult(number))
                is AppResult.Failure -> AppResult.Failure(result.error)
            }
        }

        override suspend fun dispute(
            number: PhoneNumber,
            reason: String,
        ): AppResult<Long> =
            when (val result = apiCall { api.dispute(DisputeRequest(number.e164, reason.trim())) }) {
                is AppResult.Success -> AppResult.Success(result.value.id)
                is AppResult.Failure -> AppResult.Failure(result.error)
            }

        private fun io.github.jeshiomurmu.opencaller.core.network.NumberInfoDto.toSearchResult(number: PhoneNumber) =
            SearchResult(
                number = number,
                displayName = business?.name ?: name,
                nameSource =
                    when {
                        business != null -> NameSource.VERIFIED_BUSINESS
                        name != null -> NameSource.SELF_REGISTERED
                        else -> null
                    },
                spamScore = spamScore?.toFloat()?.coerceIn(0f, 1f),
                label = label,
                category = category,
                reportCount = reportCount,
                notSpamCount = notSpamCount,
                businessName = business?.name,
            )

        /** Quota. */
        companion object {
            /** Searches per day (docs/rules.md §7). */
            const val DAILY_LIMIT = 50
            internal const val DAY_SECONDS = 86_400L
            internal const val MILLIS = 1_000L
        }
    }

/** Search timestamps and the server's block, in a small Preferences DataStore. */
class SearchQuotaStore(
    private val dataStore: DataStore<Preferences>,
) {
    /** Quota as of [now]. */
    fun quota(now: () -> Long): Flow<SearchQuota> =
        dataStore.data.map { prefs ->
            val at = now()
            val recent = prefs.searches().count { it > at - DAY_MILLIS }
            val blocked = prefs[BLOCKED_UNTIL]?.takeIf { it > at }
            SearchQuota(
                limit = SearchRepository.DAILY_LIMIT,
                remaining = if (blocked != null) 0 else (SearchRepository.DAILY_LIMIT - recent).coerceAtLeast(0),
                blockedUntil = blocked,
            )
        }

    /** Records one search at [at]. */
    suspend fun recordSearch(at: Long) {
        dataStore.edit { prefs ->
            prefs[SEARCHES] = (prefs.searches().filter { it > at - DAY_MILLIS } + at).joinToString(",")
        }
    }

    /** Remembers the server's "quota used up" until [until]. */
    suspend fun blockUntil(until: Long) {
        dataStore.edit { it[BLOCKED_UNTIL] = until }
    }

    /** The current quota (for tests). */
    suspend fun current(now: Long): SearchQuota = quota { now }.first()

    private fun Preferences.searches(): List<Long> = this[SEARCHES]?.split(',')?.mapNotNull(String::toLongOrNull).orEmpty()

    /** Storage. */
    companion object {
        /** DataStore file name. */
        const val FILE_NAME = "search_quota"
        private val SEARCHES = stringPreferencesKey("searches")
        private val BLOCKED_UNTIL = longPreferencesKey("blocked_until")
        private const val DAY_MILLIS = SearchRepository.DAY_SECONDS * SearchRepository.MILLIS
    }
}
