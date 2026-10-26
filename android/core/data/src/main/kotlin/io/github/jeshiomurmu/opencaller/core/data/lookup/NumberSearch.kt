package io.github.jeshiomurmu.opencaller.core.data.lookup

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import kotlinx.coroutines.flow.Flow

/** What an explicit search found. [spamScore] is `null` when nobody reported the number. */
data class SearchResult(
    val number: PhoneNumber,
    val displayName: String?,
    val nameSource: NameSource?,
    val spamScore: Float?,
    val label: String?,
    val category: String?,
    val reportCount: Int,
    val notSpamCount: Int,
    val businessName: String?,
)

/**
 * Searches left today. [remaining] counts this phone's searches of the last 24 h (the server allows 50 a day per
 * user); [blockedUntil] is set (epoch millis) after the server said the quota is used up.
 */
data class SearchQuota(
    val limit: Int,
    val remaining: Int,
    val blockedUntil: Long?,
)

/** Explicit number search and disputes (both need sign-in). */
interface NumberSearch {
    /** Today's remaining searches. */
    val quota: Flow<SearchQuota>

    /** Searches for [number]. */
    suspend fun search(number: PhoneNumber): AppResult<SearchResult>

    /** Disputes the spam label of [number] (for example the user's own number) with [reason]; returns the dispute id. */
    suspend fun dispute(
        number: PhoneNumber,
        reason: String,
    ): AppResult<Long>
}
