package io.github.jeshiomurmu.opencaller.core.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.Storage
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.SpamListEntry
import io.github.jeshiomurmu.opencaller.core.network.ListFileRefDto
import io.github.jeshiomurmu.opencaller.core.network.ListManifestDto
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerApi
import io.github.jeshiomurmu.opencaller.core.network.SpamListFile
import io.github.jeshiomurmu.opencaller.core.network.SpamListFormat
import io.github.jeshiomurmu.opencaller.core.network.SpamListFormatException
import io.github.jeshiomurmu.opencaller.core.network.SpamListKind
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import io.github.jeshiomurmu.opencaller.core.network.updatePlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** The user's country for the spam list (ISO 3166-1 alpha-2), or `null` when unknown. Bound in `core:telephony`. */
fun interface CountrySource {
    /** The current country. */
    fun country(): String?
}

/** Result of one update. */
sealed interface SpamListSyncOutcome {
    /** The phone already had the latest version. */
    data object UpToDate : SpamListSyncOutcome

    /** The list is now [version] of [country], applied from [files] downloads ([full] = complete list). */
    data class Updated(
        val country: String,
        val version: Long,
        val files: Int,
        val full: Boolean,
    ) : SpamListSyncOutcome

    /** The server has no list for the country yet. */
    data object NothingPublished : SpamListSyncOutcome

    /** The phone doesn't know its country (no SIM, network or locale country). */
    data object NoCountry : SpamListSyncOutcome
}

/**
 * Keeps the on-device spam list current (docs/architecture.md §4.8, §13): reads the manifest, downloads the chain
 * of deltas from the phone's version (or the full list), checks every file's SHA-256 against the manifest and its
 * own trailer, and applies everything in one Room transaction. Any failure (network, checksum, format, storage)
 * leaves the old list in place (TC-SYNC-002).
 */
@Singleton
class SpamListSyncRepository
    @Inject
    constructor(
        private val api: OpenCallerApi,
        private val access: DatabaseAccess,
        private val dispatchers: DispatcherProvider,
        private val clock: Clock,
        private val stateStore: SpamListSyncStateStore,
        private val country: CountrySource,
    ) {
        private val storage = Storage(access, dispatchers)

        /** What the phone has. */
        val state: Flow<SpamListSyncState> = stateStore.state

        /** Brings the list up to date. */
        suspend fun sync(): AppResult<SpamListSyncOutcome> =
            withContext(dispatchers.io) {
                val iso = country.country()?.uppercase()
                if (iso == null) {
                    AppResult.Success(SpamListSyncOutcome.NoCountry)
                } else {
                    when (val manifest = apiCall { api.listManifest(iso) }) {
                        is AppResult.Success -> {
                            update(manifest.value)
                        }

                        is AppResult.Failure -> {
                            if (manifest.error == AppError.NotFound) {
                                AppResult.Success(SpamListSyncOutcome.NothingPublished)
                            } else {
                                AppResult.Failure(manifest.error)
                            }
                        }
                    }
                }
            }

        private suspend fun update(manifest: ListManifestDto): AppResult<SpamListSyncOutcome> {
            val local = stateStore.state.first()
            val plan = manifest.updatePlan(local.country, local.version)
            if (plan == null) {
                stateStore.save(local.copy(checkedAt = clock.nowMillis()))
                return AppResult.Success(SpamListSyncOutcome.UpToDate)
            }
            return when (val files = downloadAll(manifest.countryIso, plan)) {
                is AppResult.Success -> apply(manifest, files.value)
                is AppResult.Failure -> AppResult.Failure(files.error)
            }
        }

        private suspend fun downloadAll(
            country: String,
            plan: List<ListFileRefDto>,
        ): AppResult<List<SpamListFile>> {
            val files = mutableListOf<SpamListFile>()
            for (ref in plan) {
                when (val file = download(country, ref)) {
                    is AppResult.Success -> files += file.value
                    is AppResult.Failure -> return AppResult.Failure(file.error)
                }
            }
            return AppResult.Success(files)
        }

        // One Room transaction: a full list replaces everything, deltas apply in order; then the state is saved.
        private suspend fun apply(
            manifest: ListManifestDto,
            files: List<SpamListFile>,
        ): AppResult<SpamListSyncOutcome> {
            val full = files.singleOrNull()?.takeIf { it.kind == SpamListKind.FULL }
            val applied =
                storage.run {
                    if (full != null) {
                        spamListDao().replaceAll(full.entries.map { it.toRow(full.version) })
                    } else {
                        spamListDao().applyDeltas(files.map { f -> f.entries.map { it.toRow(f.version) } to f.removals }, manifest.version)
                    }
                }
            if (applied is AppResult.Failure) return AppResult.Failure(applied.error)
            stateStore.save(SpamListSyncState(manifest.countryIso, manifest.version, clock.nowMillis()))
            Timber.i("Spam list %s now at v%d (%d file(s))", manifest.countryIso, manifest.version, files.size)
            return AppResult.Success(SpamListSyncOutcome.Updated(manifest.countryIso, manifest.version, files.size, full != null))
        }

        // Downloads one file and checks it: SHA-256 as listed in the manifest, then the file's own trailer, country,
        // version and kind.
        private suspend fun download(
            country: String,
            ref: ListFileRefDto,
        ): AppResult<SpamListFile> {
            val bytes =
                when (val body = apiCall { api.download(ref.url.removePrefix("/")) }) {
                    is AppResult.Success -> readFully(body.value)
                    is AppResult.Failure -> return AppResult.Failure(body.error)
                }
            val file = bytes?.let { decodeChecked(it, ref) }
            val expectedKind = if (ref.baseVersion == null) SpamListKind.FULL else SpamListKind.DELTA
            val matching =
                file?.takeIf {
                    it.countryIso == country &&
                        it.version == ref.version &&
                        it.kind == expectedKind &&
                        (ref.baseVersion == null || it.baseVersion == ref.baseVersion)
                }
            return when {
                bytes == null -> AppResult.Failure(AppError.Network)
                matching != null -> AppResult.Success(matching)
                else -> invalid("file v${ref.version} doesn't match the manifest")
            }
        }

        // The file if its SHA-256 is the manifest's and it decodes (trailer, magic, ranges, order); else null.
        private fun decodeChecked(
            bytes: ByteArray,
            ref: ListFileRefDto,
        ): SpamListFile? =
            if (SpamListFormat.sha256Hex(bytes) != ref.sha256) {
                Timber.w("Checksum mismatch for list v%d", ref.version)
                null
            } else {
                try {
                    SpamListFormat.decode(bytes)
                } catch (e: SpamListFormatException) {
                    Timber.w(e, "Unreadable list file v%d", ref.version)
                    null
                }
            }

        // The body streams from the network, so reading it can still fail after the response arrived.
        private fun readFully(body: okhttp3.ResponseBody): ByteArray? =
            try {
                body.use { it.bytes() }
            } catch (e: java.io.IOException) {
                Timber.w(e, "List download interrupted")
                null
            }

        private fun invalid(message: String): AppResult.Failure {
            Timber.w("Spam list update rejected: %s", message)
            return AppResult.Failure(AppError.Validation(message))
        }

        private fun io.github.jeshiomurmu.opencaller.core.network.SpamListFileEntry.toRow(version: Long) =
            SpamListEntry(
                e164 = e164,
                score = score,
                category = category.orEmpty(),
                reportCount = reportCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                listVersion = version,
            )
    }

/** Persists [SpamListSyncState] (a small Preferences DataStore of its own). */
class SpamListSyncStateStore(
    private val dataStore: DataStore<Preferences>,
) {
    /** The stored state. */
    val state: Flow<SpamListSyncState> =
        dataStore.data.map { SpamListSyncState(it[COUNTRY], it[VERSION], it[CHECKED_AT]) }

    /** Replaces the stored state. */
    suspend fun save(state: SpamListSyncState) {
        dataStore.edit { prefs ->
            state.country?.let { prefs[COUNTRY] = it } ?: prefs.remove(COUNTRY)
            state.version?.let { prefs[VERSION] = it } ?: prefs.remove(VERSION)
            state.checkedAt?.let { prefs[CHECKED_AT] = it } ?: prefs.remove(CHECKED_AT)
        }
    }

    /** Storage keys. */
    companion object {
        /** DataStore file name. */
        const val FILE_NAME = "spam_list_sync"
        private val COUNTRY = stringPreferencesKey("country")
        private val VERSION = longPreferencesKey("version")
        private val CHECKED_AT = longPreferencesKey("checked_at")
    }
}
