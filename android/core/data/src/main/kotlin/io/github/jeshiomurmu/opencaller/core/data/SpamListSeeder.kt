package io.github.jeshiomurmu.opencaller.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

/**
 * Parses the bundled seed spam list: CSV lines `e164,score,category,reportCount`; `#` comments, blank lines and
 * invalid rows are skipped.
 */
object SpamListSeedParser {
    private const val COLUMNS = 4
    private const val NUMBER = 0
    private const val SCORE = 1
    private const val CATEGORY = 2
    private const val REPORTS = 3

    /** The valid entries of [lines]. */
    fun parse(lines: Sequence<String>): List<SpamListHit> =
        lines
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull(::parseLine)
            .toList()

    private fun parseLine(line: String): SpamListHit? {
        val parts = line.split(',').map(String::trim)
        val score = parts.getOrNull(SCORE)?.toFloatOrNull()?.takeIf { it in 0f..1f }
        val reports = parts.getOrNull(REPORTS)?.toIntOrNull()?.takeIf { it >= 0 }
        val valid = parts.size == COLUMNS && PhoneNumber.isE164(parts[NUMBER]) && score != null && reports != null
        return if (valid) SpamListHit(PhoneNumber(parts[NUMBER]), checkNotNull(score), parts[CATEGORY], checkNotNull(reports)) else null
    }
}

/**
 * Loads the seed spam list bundled in `assets/spam_list/seed.csv` into an empty spam list at app start. The
 * repository's copy is empty on purpose (no list is published yet); tests use the `spam_list_small.csv` fixture.
 */
class SpamListSeeder
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: SpamListRepository,
        private val dispatchers: DispatcherProvider,
    ) {
        /** Seeds the list when it is empty; returns the number of entries added. */
        suspend fun seedIfEmpty(): AppResult<Int> =
            repository.count().flatMap { count ->
                if (count > 0) AppResult.Success(0) else readSeed().flatMap { save(it) }
            }

        private suspend fun readSeed(): AppResult<List<SpamListHit>> =
            try {
                AppResult.Success(
                    withContext(dispatchers.io) {
                        context.assets
                            .open(SEED_ASSET)
                            .bufferedReader()
                            .useLines { SpamListSeedParser.parse(it) }
                    },
                )
            } catch (e: IOException) {
                Timber.w(e, "Could not read the seed spam list")
                AppResult.Failure(AppError.Storage)
            }

        private suspend fun save(hits: List<SpamListHit>): AppResult<Int> =
            if (hits.isEmpty()) AppResult.Success(0) else repository.upsert(hits, SEED_LIST_VERSION).map { hits.size }

        private companion object {
            const val SEED_ASSET = "spam_list/seed.csv"
            const val SEED_LIST_VERSION = 1L
        }
    }
