package io.github.jeshiomurmu.opencaller.core.data.lookup

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.data.Storage
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.LookupBucketEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.NumberInfoEntity
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.network.LookupApi
import io.github.jeshiomurmu.opencaller.core.network.NumberInfoDto
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import io.github.jeshiomurmu.opencaller.core.network.ServerConfig
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import io.github.jeshiomurmu.opencaller.core.phonenumber.BucketHasher
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a live lookup found for a number.
 *
 * @property spamScore community score in `0.0..1.0`, `null` without reports.
 * @property fromCache true when the answer came from an earlier lookup (cached 24 h) rather than the network now.
 */
data class LiveLookupResult(
    val spamScore: Float?,
    val category: String?,
    val reportCount: Int,
    val displayName: String?,
    val nameSource: NameSource?,
    val fromCache: Boolean,
)

/**
 * The k-anonymous live lookup (docs/architecture.md §4.1 step 10, §4.6). The phone hashes the number
 * (SHA-256 of the E.164 string) and asks for its 16-bit bucket only: the request carries the country calling code
 * and the bucket number, never the number (TC-LOOK-002). The full hash is matched on the phone. Answers, including
 * "nothing known", are cached for 24 h in `number_info`; the bucket fetch is recorded in `lookup_bucket`. A slow or
 * unreachable server gives no answer after [OpenCallerNetwork.DEFAULT_LOOKUP_TIMEOUT_MILLIS] (TC-LOOK-001).
 */
@Singleton
class LiveLookupRepository
    @Inject
    constructor(
        private val lookupApi: LookupApi,
        private val server: ServerConfig,
        private val normalizer: PhoneNumberNormalizer,
        private val clock: Clock,
        access: DatabaseAccess,
        dispatchers: DispatcherProvider,
    ) {
        private val storage = Storage(access, dispatchers)
        private val hasher = BucketHasher(BucketHasher.DEFAULT_BITS)

        /** What is known about [number], or `null` when nothing is known, the server is slow or unreachable. */
        suspend fun lookup(number: PhoneNumber): LiveLookupResult? {
            val now = clock.nowMillis()
            val cached = if (server.isConfigured) storage.run { numberInfoDao().findFresh(number.e164, now) }.getOrNull() else null
            return when {
                !server.isConfigured -> null
                cached != null -> cached.toResult()
                else -> fetch(number, now)
            }
        }

        // One bucket request (calling code + 16-bit bucket only), matched on the phone and cached.
        private suspend fun fetch(
            number: PhoneNumber,
            now: Long,
        ): LiveLookupResult? {
            val countryCode = normalizer.countryCallingCode(number)
            val bucket = hasher.bucket(number)
            val response =
                withTimeoutOrNull(OpenCallerNetwork.DEFAULT_LOOKUP_TIMEOUT_MILLIS) {
                    apiCall { lookupApi.bucket(countryCode, bucket, BucketHasher.DEFAULT_BITS) }
                }
            val entries = (response as? AppResult.Success)?.value?.entries ?: return null
            val hash = Base64.getEncoder().encodeToString(hasher.digest(number))
            val row = entries.firstOrNull { it.hash == hash }?.info.toRow(number, now)
            storage.run {
                numberInfoDao().upsert(row)
                lookupBucketDao().upsert(LookupBucketEntity(countryCode, bucket, now, etag = null))
            }
            return row.toResult().copy(fromCache = false)
        }

        // Absent from the bucket = nothing known; cached too, so the next call from this number skips the network.
        private fun NumberInfoDto?.toRow(
            number: PhoneNumber,
            now: Long,
        ): NumberInfoEntity {
            val business = this?.business
            val name = business?.name ?: this?.name
            return NumberInfoEntity(
                e164 = number.e164,
                displayName = name,
                nameSource =
                    when {
                        business != null -> NameSource.VERIFIED_BUSINESS
                        name != null -> NameSource.SELF_REGISTERED
                        else -> null
                    },
                spamScore = this?.spamScore?.toFloat()?.coerceIn(0f, 1f),
                spamCategory = this?.category,
                reportCount = this?.reportCount ?: 0,
                isBusiness = business != null,
                businessId = business?.let { VERIFIED_BUSINESS_ID },
                verified = business?.verified == true,
                logoUrl = null,
                callReason = business?.callReason,
                lineType = null,
                fetchedAt = now,
                expiresAt = now + CACHE_MILLIS,
            )
        }

        private fun NumberInfoEntity.toResult() =
            LiveLookupResult(
                spamScore = spamScore,
                category = spamCategory,
                reportCount = reportCount,
                displayName = displayName,
                nameSource = nameSource.takeIf { displayName != null },
                fromCache = true,
            )

        private companion object {
            // docs/architecture.md §4.6: buckets are cacheable for 24 h.
            const val CACHE_MILLIS = 24 * 60 * 60 * 1_000L

            // The server identifies verified businesses but sends no id yet (business profiles arrive in Phase 10).
            const val VERIFIED_BUSINESS_ID = "verified"
        }
    }
