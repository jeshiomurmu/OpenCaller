package io.github.jeshiomurmu.opencaller.core.data.business

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.data.Storage
import io.github.jeshiomurmu.opencaller.core.data.sync.CountrySource
import io.github.jeshiomurmu.opencaller.core.data.sync.RegistrySyncStateStore
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.VerifiedBusinessEntity
import io.github.jeshiomurmu.opencaller.core.model.BusinessCategory
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.VerifiedBusiness
import io.github.jeshiomurmu.opencaller.core.network.BusinessApi
import io.github.jeshiomurmu.opencaller.core.network.DirectoryEntryDto
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Verified businesses known on the phone. */
fun interface BusinessDirectory {
    /** The verified business behind [number], or `null`. */
    suspend fun find(number: PhoneNumber): AppResult<VerifiedBusiness?>
}

/**
 * The verified-business directory of the phone's country on the phone (docs/prd.md F-BZ-02): synced daily with
 * the other registries (changed rows only, `?since=`; revoked businesses are removed), with each business's logo
 * downloaded once into [BusinessLogoStore]. Calls and messages read it locally, so a verified business is known
 * offline and before any lookup, and "block non-contacts" lets it ring. The requests carry only the country.
 */
@Singleton
class BusinessDirectoryRepository
    @Inject
    constructor(
        private val api: BusinessApi,
        access: DatabaseAccess,
        dispatchers: DispatcherProvider,
        private val stateStore: RegistrySyncStateStore,
        private val country: CountrySource,
        private val logos: BusinessLogoStore,
    ) : BusinessDirectory {
        private val storage = Storage(access, dispatchers)

        override suspend fun find(number: PhoneNumber): AppResult<VerifiedBusiness?> =
            storage.run { verifiedBusinessDao().find(number.e164) }.map { row -> row?.toModel() }

        /** Updates the directory of the phone's country, then fetches missing logos; returns the changed rows. */
        suspend fun sync(): AppResult<Int> {
            val iso = country.country()?.uppercase() ?: return AppResult.Success(0)
            val state = stateStore.state.first()
            val sameCountry = state.businessCountry == iso
            val cleared = if (sameCountry) AppResult.Success(Unit) else storage.run { verifiedBusinessDao().clear() }
            return cleared
                .flatMap { pages(iso, since = state.businessSince.takeIf { sameCountry }, stored = 0, page = 0) }
                .flatMap { changed -> fetchLogos().map { changed } }
        }

        // Pages of at most 5,000 rows: ask again from the page's time until a page comes back short.
        private suspend fun pages(
            iso: String,
            since: Long?,
            stored: Int,
            page: Int,
        ): AppResult<Int> =
            apiCall { api.directory(iso, since) }.flatMap { response ->
                val (removed, current) = response.entries.filter { PhoneNumber.isE164(it.e164) }.partition { it.removed }
                storage
                    .run {
                        verifiedBusinessDao().upsertAll(current.map { it.toEntity() })
                        if (removed.isNotEmpty()) verifiedBusinessDao().deleteAll(removed.map { it.e164 })
                    }.flatMap {
                        stateStore.update { it.copy(businessCountry = iso, businessSince = response.syncedAt) }
                        val total = stored + response.entries.size
                        if (response.entries.size < PAGE_SIZE || page + 1 >= MAX_PAGES) {
                            AppResult.Success(total)
                        } else {
                            pages(iso, response.syncedAt, total, page + 1)
                        }
                    }
            }

        // A logo that fails is tried again at the next update; only a storage failure fails the update.
        private suspend fun fetchLogos(): AppResult<Unit> =
            storage.run { verifiedBusinessDao().missingLogos(LOGOS_PER_UPDATE) }.flatMap { rows ->
                rows.distinctBy { it.businessId }.forEach { row -> row.logoPath?.let { fetchLogo(row.businessId, it) } }
                storage.run { verifiedBusinessDao().logoFiles() }.map { logos.keepOnly(it) }
            }

        private suspend fun fetchLogo(
            businessId: String,
            path: String,
        ) {
            if (!LOGO_PATH.matches(path) || !path.contains(businessId)) {
                Timber.w("Ignoring an unexpected logo path")
                return
            }
            when (val download = apiCall { api.logo(path.removePrefix("/")) }.readLimited(LOGO_LIMIT)) {
                is AppResult.Success -> store(businessId, download.value)
                is AppResult.Failure -> Timber.w("Logo download failed: %s", download.error)
            }
        }

        private suspend fun store(
            businessId: String,
            bytes: ByteArray?,
        ) {
            if (bytes == null || BusinessLogoStore.fileName(businessId, bytes) == null) {
                Timber.w("Ignoring a logo that isn't a small PNG or JPEG")
                return
            }
            try {
                val name = logos.write(businessId, bytes)
                storage.run { verifiedBusinessDao().setLogoFile(businessId, name) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Timber.w(e, "Logo not stored")
            }
        }

        private fun VerifiedBusinessEntity.toModel() =
            VerifiedBusiness(
                number = PhoneNumber(e164),
                businessId = businessId,
                name = name,
                category = BusinessCategory.parse(category),
                callReason = callReason,
                logoPath = logos.path(logoFile),
            )

        private fun DirectoryEntryDto.toEntity() =
            VerifiedBusinessEntity(
                e164 = e164,
                businessId = businessId,
                name = name,
                category = category,
                callReason = callReason,
                logoPath = logoUrl,
                logoFile = null,
                updatedAt = updatedAt,
            )

        private companion object {
            const val PAGE_SIZE = 5_000
            const val MAX_PAGES = 20
            const val LOGOS_PER_UPDATE = 100
            const val LOGO_LIMIT = BusinessLogoStore.MAX_BYTES.toLong()
            val LOGO_PATH = Regex("/v1/business/[0-9a-f-]{36}/logo")
        }
    }
