package io.github.jeshiomurmu.opencaller.server.billing

import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.Subscriptions
import io.github.jeshiomurmu.opencaller.server.db.Users
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A verified Play purchase as stored in `subscriptions`. */
data class SubscriptionRecord(
    val purchaseToken: String,
    val productId: String,
    val basePlanId: String,
    val tier: PaidTier,
    val state: String,
    val expiresAt: Instant?,
    val entitledUntil: Instant?,
    val autoRenewing: Boolean,
    val acknowledged: Boolean,
    val testPurchase: Boolean,
) {
    /** `true` while the purchase gives access at [now]. */
    fun entitledAt(now: Instant): Boolean = entitledUntil?.isAfter(now) == true
}

/**
 * `subscriptions` rows of the Play platform and the `users.supporter_tier` they set. A user with a Play row gets the
 * row's tier while it is entitled and `none` after; users without one keep whatever tier the operator gave them.
 */
class SubscriptionStore(
    private val db: ServerDatabase,
) {
    /** The user's Play purchase, if any. */
    suspend fun find(userId: UUID): SubscriptionRecord? =
        db.query {
            Subscriptions
                .selectAll()
                .where { (Subscriptions.userId eq userId) and (Subscriptions.platform eq PLATFORM) }
                .singleOrNull()
                ?.let(::record)
        }

    /** The user who verified [purchaseToken] before, if anyone did. */
    suspend fun ownerOf(purchaseToken: String): UUID? =
        db.query {
            Subscriptions
                .select(Subscriptions.userId)
                .where { Subscriptions.purchaseTokenHash eq tokenHash(purchaseToken) }
                .singleOrNull()
                ?.get(Subscriptions.userId)
        }

    /** Stores [record] as the user's Play purchase and updates their tier; returns `true` if the tier changed. */
    suspend fun save(
        userId: UUID,
        record: SubscriptionRecord,
        now: Instant,
    ): Boolean =
        db.query {
            Subscriptions.upsert {
                it[Subscriptions.userId] = userId
                it[platform] = PLATFORM
                it[productId] = record.productId
                it[purchaseTokenHash] = tokenHash(record.purchaseToken)
                it[purchaseToken] = record.purchaseToken
                it[status] = record.state
                it[expiresAt] = record.expiresAt?.let(::utc)
                it[basePlanId] = record.basePlanId
                it[tier] = record.tier.value
                it[autoRenewing] = record.autoRenewing
                it[acknowledged] = record.acknowledged
                it[testPurchase] = record.testPurchase
                it[entitledUntil] = record.entitledUntil?.let(::utc)
                it[checkedAt] = utc(now)
            }
            applyTier(userId, if (record.entitledAt(now)) record.tier.value else NONE)
        }

    /** Marks the user's Play purchase as acknowledged. */
    suspend fun markAcknowledged(userId: UUID) {
        db.query {
            Subscriptions.update({ (Subscriptions.userId eq userId) and (Subscriptions.platform eq PLATFORM) }) {
                it[acknowledged] = true
            }
        }
    }

    /**
     * Purchases to ask Google about again: access ends within [horizonHours] (renewals happen at the end of a
     * period) or ended less than a week ago (a late renewal or a recovered payment), not checked in the last hour.
     */
    suspend fun dueForRecheck(
        now: Instant,
        limit: Int,
        horizonHours: Long = RECHECK_HORIZON_HOURS,
    ): List<Pair<UUID, String>> =
        db.query {
            Subscriptions
                .select(Subscriptions.userId, Subscriptions.purchaseToken)
                .where {
                    (Subscriptions.platform eq PLATFORM) and Subscriptions.purchaseToken.isNotNull() and
                        (Subscriptions.entitledUntil less utc(now.plusSeconds(horizonHours * SECONDS_PER_HOUR))) and
                        (Subscriptions.entitledUntil greater utc(now.minusSeconds(RECHECK_BACK_DAYS * SECONDS_PER_DAY))) and
                        (Subscriptions.checkedAt less utc(now.minusSeconds(SECONDS_PER_HOUR)))
                }.orderBy(Subscriptions.entitledUntil)
                .limit(limit)
                .map { it[Subscriptions.userId] to checkNotNull(it[Subscriptions.purchaseToken]) }
        }

    /** Sets every Play user's tier from their purchase as of [now] (access ending drops the tier); returns how many changed. */
    suspend fun syncTiers(now: Instant): Int =
        db.query {
            Subscriptions
                .select(Subscriptions.userId, Subscriptions.tier, Subscriptions.entitledUntil)
                .where { Subscriptions.platform eq PLATFORM }
                .toList()
                .count { row ->
                    val entitled = row[Subscriptions.entitledUntil]?.toInstant()?.isAfter(now) == true
                    applyTier(row[Subscriptions.userId], if (entitled) row[Subscriptions.tier] else NONE)
                }
        }

    private fun JdbcTransaction.applyTier(
        userId: UUID,
        tier: String,
    ): Boolean = Users.update({ (Users.id eq userId) and (Users.supporterTier neq tier) }) { it[supporterTier] = tier } > 0

    private fun record(row: ResultRow): SubscriptionRecord =
        SubscriptionRecord(
            purchaseToken = row[Subscriptions.purchaseToken].orEmpty(),
            productId = row[Subscriptions.productId],
            basePlanId = row[Subscriptions.basePlanId].orEmpty(),
            tier = PaidTier.entries.firstOrNull { it.value == row[Subscriptions.tier] } ?: PaidTier.SUPPORTER,
            state = row[Subscriptions.status],
            expiresAt = row[Subscriptions.expiresAt]?.toInstant(),
            entitledUntil = row[Subscriptions.entitledUntil]?.toInstant(),
            autoRenewing = row[Subscriptions.autoRenewing],
            acknowledged = row[Subscriptions.acknowledged],
            testPurchase = row[Subscriptions.testPurchase],
        )

    /** Platform name and limits. */
    companion object {
        /** `subscriptions.platform` of Play purchases. */
        const val PLATFORM = "google_play"
        private const val NONE = "none"
        private const val RECHECK_HORIZON_HOURS = 24L
        private const val RECHECK_BACK_DAYS = 7L
        private const val SECONDS_PER_HOUR = 3600L
        private const val SECONDS_PER_DAY = 86_400L

        /** SHA-256 hex of a purchase token (unique per stored purchase). */
        fun tokenHash(purchaseToken: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(purchaseToken.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)
    }
}
