package io.github.jeshiomurmu.opencaller.core.data

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.NumberInfoEntity
import io.github.jeshiomurmu.opencaller.core.model.LineType
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the reputation cache knows about a number (filled by lookups from Phase 5 on).
 *
 * @property spamScore cached spam score in `0.0..1.0`, if any.
 * @property verifiedBusinessId business id when the number belongs to a verified business.
 */
data class CachedReputation(
    val displayName: String?,
    val nameSource: NameSource?,
    val spamScore: Float?,
    val spamCategory: String?,
    val reportCount: Int,
    val verifiedBusinessId: String?,
    val logoUrl: String?,
    val callReason: String?,
    val lineType: LineType?,
)

/** Reputation cache (docs/architecture.md §4.1 step 9): entries expire after their TTL (24 h). */
@Singleton
class ReputationRepository
    @Inject
    constructor(
        access: DatabaseAccess,
        dispatchers: DispatcherProvider,
        private val clock: Clock,
    ) {
        private val storage = Storage(access, dispatchers)

        /** Unexpired cached information for [number], or `null`. */
        suspend fun findFresh(number: PhoneNumber): AppResult<CachedReputation?> =
            storage.run { numberInfoDao().findFresh(number.e164, clock.nowMillis()) }.map { it?.toReputation() }

        private fun NumberInfoEntity.toReputation(): CachedReputation =
            CachedReputation(
                displayName = displayName,
                nameSource = nameSource,
                spamScore = spamScore?.coerceIn(0f, 1f),
                spamCategory = spamCategory,
                reportCount = reportCount.coerceAtLeast(0),
                verifiedBusinessId = businessId.takeIf { verified },
                logoUrl = logoUrl,
                callReason = callReason,
                lineType = lineType?.let { name -> LineType.entries.firstOrNull { it.name == name } },
            )
    }
