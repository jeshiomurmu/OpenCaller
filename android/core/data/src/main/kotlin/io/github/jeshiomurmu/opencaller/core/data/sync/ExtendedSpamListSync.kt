package io.github.jeshiomurmu.opencaller.core.data.sync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.data.Storage
import io.github.jeshiomurmu.opencaller.core.data.supporter.EntitlementProvider
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.ExtendedSpamListEntry
import io.github.jeshiomurmu.opencaller.core.network.BillingApi
import io.github.jeshiomurmu.opencaller.core.network.ListFileRefDto
import io.github.jeshiomurmu.opencaller.core.network.ListManifestDto
import io.github.jeshiomurmu.opencaller.core.network.SpamListFile
import io.github.jeshiomurmu.opencaller.core.network.SpamListFormat
import io.github.jeshiomurmu.opencaller.core.network.SpamListFormatException
import io.github.jeshiomurmu.opencaller.core.network.SpamListKind
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What one extended-list update did. */
enum class ExtendedListOutcome {
    /** The phone already has the manifest's extended list. */
    UP_TO_DATE,

    /** Downloaded and stored the manifest's extended list. */
    UPDATED,

    /** Not a supporter, or the country has no extended list: the phone keeps none. */
    NONE,
}

/**
 * The extended spam list (docs/prd.md F-PR-03): with the standard list's manifest in hand, a supporter downloads
 * the version's extended file (with the access token), checks it like any list file and replaces the
 * `spam_list_extended` table; anyone else keeps no extended list on the phone. A failure leaves the old one.
 */
@Singleton
class ExtendedSpamListSync
    @Inject
    constructor(
        private val api: BillingApi,
        private val access: DatabaseAccess,
        private val dispatchers: DispatcherProvider,
        private val entitlements: EntitlementProvider,
        private val state: ExtendedListStateStore,
    ) {
        private val storage = Storage(access, dispatchers)

        /** Brings the extended list in line with [manifest]. */
        suspend fun sync(manifest: ListManifestDto): AppResult<ExtendedListOutcome> {
            val ref = manifest.extended
            return when {
                ref == null || !entitlements.current().entitled -> clear()
                state.version(manifest.countryIso) == ref.version -> AppResult.Success(ExtendedListOutcome.UP_TO_DATE)
                else -> fetch(manifest.countryIso, ref).flatMap { file -> store(manifest.countryIso, file) }
            }
        }

        private suspend fun fetch(
            country: String,
            ref: ListFileRefDto,
        ): AppResult<SpamListFile> =
            when (val body = apiCall { api.downloadExtended(ref.url.removePrefix("/")) }) {
                is AppResult.Failure -> {
                    AppResult.Failure(body.error)
                }

                is AppResult.Success -> {
                    val bytes = readFully(body.value)
                    val file =
                        bytes?.let { decodeChecked(it, ref) }?.takeIf {
                            it.kind == SpamListKind.EXTENDED && it.countryIso == country && it.version == ref.version
                        }
                    when {
                        bytes == null -> AppResult.Failure(AppError.Network)
                        file == null -> AppResult.Failure(AppError.Validation("extended list v${ref.version} doesn't match the manifest"))
                        else -> AppResult.Success(file)
                    }
                }
            }

        private suspend fun store(
            country: String,
            file: SpamListFile,
        ): AppResult<ExtendedListOutcome> =
            when (val stored = storage.run { extendedSpamListDao().replaceAll(file.entries.map { it.toRow(file.version) }) }) {
                is AppResult.Failure -> {
                    AppResult.Failure(stored.error)
                }

                is AppResult.Success -> {
                    state.save(country, file.version)
                    Timber.i("Extended spam list %s now at v%d (%d numbers)", country, file.version, file.entries.size)
                    AppResult.Success(ExtendedListOutcome.UPDATED)
                }
            }

        /** Drops the extended list (no longer a supporter, or none published). */
        suspend fun clear(): AppResult<ExtendedListOutcome> {
            val cleared = storage.run { extendedSpamListDao().deleteAll() }
            if (cleared is AppResult.Failure) return AppResult.Failure(cleared.error)
            state.save(null, null)
            return AppResult.Success(ExtendedListOutcome.NONE)
        }

        private fun decodeChecked(
            bytes: ByteArray,
            ref: ListFileRefDto,
        ): SpamListFile? =
            if (SpamListFormat.sha256Hex(bytes) != ref.sha256) {
                Timber.w("Checksum mismatch for extended list v%d", ref.version)
                null
            } else {
                try {
                    SpamListFormat.decode(bytes)
                } catch (e: SpamListFormatException) {
                    Timber.w(e, "Unreadable extended list v%d", ref.version)
                    null
                }
            }

        private fun readFully(body: okhttp3.ResponseBody): ByteArray? =
            try {
                body.use { it.bytes() }
            } catch (e: IOException) {
                Timber.w(e, "Extended list download interrupted")
                null
            }

        private fun io.github.jeshiomurmu.opencaller.core.network.SpamListFileEntry.toRow(version: Long) =
            ExtendedSpamListEntry(
                e164 = e164,
                score = score,
                category = category.orEmpty(),
                reportCount = reportCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                listVersion = version,
            )
    }

/** The extended list's country and version on this phone (a small Preferences DataStore of its own). */
class ExtendedListStateStore(
    private val dataStore: DataStore<Preferences>,
) {
    /** The stored version for [country], or `null`. */
    suspend fun version(country: String): Long? = dataStore.data.map { if (it[COUNTRY] == country) it[VERSION] else null }.first()

    /** Records [country] and [version] (both `null`: no extended list). */
    suspend fun save(
        country: String?,
        version: Long?,
    ) {
        dataStore.edit { prefs ->
            country?.let { prefs[COUNTRY] = it } ?: prefs.remove(COUNTRY)
            version?.let { prefs[VERSION] = it } ?: prefs.remove(VERSION)
        }
    }

    /** Storage keys. */
    companion object {
        /** DataStore file name. */
        const val FILE_NAME = "extended_spam_list"
        private val COUNTRY = stringPreferencesKey("country")
        private val VERSION = longPreferencesKey("version")
    }
}
