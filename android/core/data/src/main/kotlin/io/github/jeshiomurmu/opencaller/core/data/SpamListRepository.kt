package io.github.jeshiomurmu.opencaller.core.data

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.SpamListEntry
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One number on the on-device spam list.
 *
 * @property score spam score in `0.0..1.0`.
 * @property category community category such as "Loan", empty when unknown.
 */
data class SpamListHit(
    val number: PhoneNumber,
    val score: Float,
    val category: String,
    val reportCount: Int,
)

/**
 * The on-device spam list (docs/architecture.md §4.1 step 8). Phase 3 fills it from the bundled seed list and, in
 * debug builds, from QA commands; downloading it from the server comes in Phase 5.
 */
@Singleton
class SpamListRepository
    @Inject
    constructor(
        access: DatabaseAccess,
        dispatchers: DispatcherProvider,
    ) {
        private val storage = Storage(access, dispatchers)

        /** The entry for [number], or `null` when it isn't listed. */
        suspend fun find(number: PhoneNumber): AppResult<SpamListHit?> =
            storage.run { spamListDao().find(number.e164) }.map { entry -> entry?.toHit() }

        /** Adds or replaces [hits] as list version [listVersion]. */
        suspend fun upsert(
            hits: List<SpamListHit>,
            listVersion: Long,
        ): AppResult<Unit> =
            storage.run {
                spamListDao().upsertAll(hits.map { SpamListEntry(it.number.e164, it.score, it.category, it.reportCount, listVersion) })
            }

        /** Number of listed numbers. */
        suspend fun count(): AppResult<Int> = storage.run { spamListDao().count() }

        /** Deletes every entry (debug QA reset). */
        suspend fun deleteAll(): AppResult<Int> = storage.run { spamListDao().deleteAll() }

        private fun SpamListEntry.toHit(): SpamListHit? =
            e164
                .takeIf(
                    PhoneNumber::isE164,
                )?.let { SpamListHit(PhoneNumber(it), score.coerceIn(0f, 1f), category, reportCount.coerceAtLeast(0)) }
    }
