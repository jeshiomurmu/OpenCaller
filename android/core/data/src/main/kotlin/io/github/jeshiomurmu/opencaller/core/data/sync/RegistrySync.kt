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
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.data.Storage
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.OtpAppHashEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.SenderIdEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.VerificationRangeEntity
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerApi
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** What one registry update stored. */
data class RegistrySyncOutcome(
    val senderIds: Int,
    val otpSources: Int,
    val linkPrefixes: Int,
)

/** Where the registries stand on this phone: the `since` values for the next update. */
data class RegistrySyncState(
    val senderCountry: String? = null,
    val senderSince: Long? = null,
    val otpSince: Long? = null,
)

/**
 * Keeps the server's public registries on the phone (docs/architecture.md §8): the sender-ID registry of the
 * phone's country and the OTP sources (app hashes, flash-call ranges) into Room, changed rows only (`?since=`), and
 * the known-bad link prefixes into [LinkPrefixStore] (whole list). Nothing about the user is sent.
 */
@Singleton
class RegistrySyncRepository
    @Inject
    constructor(
        private val api: OpenCallerApi,
        access: DatabaseAccess,
        dispatchers: DispatcherProvider,
        private val stateStore: RegistrySyncStateStore,
        private val country: CountrySource,
        private val linkPrefixes: LinkPrefixStore,
    ) {
        private val storage = Storage(access, dispatchers)

        /** Updates all three registries; the first failure stops the update (rows stored so far stay). */
        suspend fun sync(): AppResult<RegistrySyncOutcome> {
            val state = stateStore.state.first()
            return syncSenderIds(state).flatMap { senders ->
                syncOtpSources(state).flatMap { otp ->
                    syncLinkPrefixes().map { links -> RegistrySyncOutcome(senders, otp, links) }
                }
            }
        }

        private suspend fun syncSenderIds(state: RegistrySyncState): AppResult<Int> {
            val iso = country.country()?.uppercase()
            return if (iso == null) {
                AppResult.Success(0)
            } else {
                senderPages(iso, since = state.senderSince.takeIf { state.senderCountry == iso }, stored = 0, page = 0)
            }
        }

        // Pages of at most 5,000 rows: ask again from the page's time until a page comes back short.
        private suspend fun senderPages(
            iso: String,
            since: Long?,
            stored: Int,
            page: Int,
        ): AppResult<Int> =
            apiCall { api.senderIds(iso, since) }.flatMap { response ->
                storage.run { senderIdDao().upsertAll(response.senderIds.map { it.toEntity(iso) }) }.flatMap {
                    stateStore.update { it.copy(senderCountry = iso, senderSince = response.syncedAt) }
                    val total = stored + response.senderIds.size
                    if (response.senderIds.size < PAGE_SIZE || page + 1 >= MAX_PAGES) {
                        AppResult.Success(total)
                    } else {
                        senderPages(iso, response.syncedAt, total, page + 1)
                    }
                }
            }

        private suspend fun syncOtpSources(state: RegistrySyncState): AppResult<Int> =
            apiCall { api.otpSources(state.otpSince) }.flatMap { sources ->
                storage
                    .run {
                        otpAppHashDao().upsertAll(sources.appHashes.map { OtpAppHashEntity(it.hash, it.packageName, it.appName) })
                        verificationRangeDao().upsertAll(sources.verificationRanges.map { VerificationRangeEntity(it.prefix, it.provider) })
                    }.map {
                        stateStore.update { it.copy(otpSince = sources.syncedAt) }
                        sources.appHashes.size + sources.verificationRanges.size
                    }
            }

        private suspend fun syncLinkPrefixes(): AppResult<Int> =
            apiCall { api.linkPrefixes() }.flatMap { list ->
                val prefixes = list.prefixes.map { hex -> hex.takeIf(PREFIX::matches)?.toLong(HEX)?.toInt() }
                if (null in prefixes) {
                    AppResult.Failure(AppError.Validation("invalid_link_prefix"))
                } else {
                    replacePrefixes(prefixes.filterNotNull().toIntArray())
                }
            }

        private suspend fun replacePrefixes(prefixes: IntArray): AppResult<Int> =
            try {
                linkPrefixes.replace(prefixes)
                AppResult.Success(prefixes.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Timber.w(e, "Link prefix list not stored")
                AppResult.Failure(AppError.Storage)
            }

        private fun io.github.jeshiomurmu.opencaller.core.network.SenderIdDto.toEntity(iso: String) =
            SenderIdEntity(
                countryIso = iso,
                header = header.uppercase(),
                brandName = brandName,
                category = category,
                source = source,
                businessId = businessId,
                updatedAt = updatedAt,
            )

        private companion object {
            const val PAGE_SIZE = 5_000
            const val MAX_PAGES = 20
            const val HEX = 16
            val PREFIX = Regex("[0-9a-f]{8}")
        }
    }

/** Persists [RegistrySyncState] (a small Preferences DataStore of its own). */
class RegistrySyncStateStore(
    private val dataStore: DataStore<Preferences>,
) {
    /** The stored state. */
    val state: Flow<RegistrySyncState> =
        dataStore.data.map { RegistrySyncState(it[SENDER_COUNTRY], it[SENDER_SINCE], it[OTP_SINCE]) }

    /** Changes the stored state with [change]. */
    suspend fun update(change: (RegistrySyncState) -> RegistrySyncState) {
        dataStore.edit { prefs ->
            val next = change(RegistrySyncState(prefs[SENDER_COUNTRY], prefs[SENDER_SINCE], prefs[OTP_SINCE]))
            next.senderCountry?.let { prefs[SENDER_COUNTRY] = it } ?: prefs.remove(SENDER_COUNTRY)
            next.senderSince?.let { prefs[SENDER_SINCE] = it } ?: prefs.remove(SENDER_SINCE)
            next.otpSince?.let { prefs[OTP_SINCE] = it } ?: prefs.remove(OTP_SINCE)
        }
    }

    /** Storage keys. */
    companion object {
        /** DataStore file name. */
        const val FILE_NAME = "registry_sync"
        private val SENDER_COUNTRY = stringPreferencesKey("sender_country")
        private val SENDER_SINCE = longPreferencesKey("sender_since")
        private val OTP_SINCE = longPreferencesKey("otp_since")
    }
}
