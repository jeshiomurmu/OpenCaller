package io.github.jeshiomurmu.opencaller.server.billing

import io.github.jeshiomurmu.opencaller.server.config.BillingConfig
import io.github.jeshiomurmu.opencaller.server.config.GooglePlayConfig
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.profile.SupporterTier
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration.Companion.hours

/**
 * Supporter purchases and what they unlock (docs/prd.md F-UT-08). The app buys the subscription in Play, passing
 * [accountId] as the obfuscated account ID, and sends the purchase token here; the server asks Google about it,
 * checks that it is our subscription and belongs to this account, stores it, acknowledges it and sets the user's
 * tier. Access lasts while Google says the subscription is active (plus a short slack for the renewal to show up),
 * or until the paid period ends after a cancellation. An hourly job asks Google again before access runs out.
 */
class BillingService(
    private val db: ServerDatabase,
    private val config: BillingConfig,
    private val play: PlaySubscriptions?,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
    private val store: SubscriptionStore = SubscriptionStore(db),
) {
    private val googlePlay: GooglePlayConfig? = config.googlePlay.takeIf { play != null }

    /** `GET /v1/server`. */
    fun serverInfo(features: FeatureInfo = FeatureInfo()) =
        ServerInfoResponse(config.selfHosted, BillingInfo(googlePlay?.productId), features)

    /** `GET /v1/entitlements`. */
    suspend fun entitlements(userId: UUID): EntitlementResponse {
        val tier =
            SupporterTier.of(
                db.query {
                    Users
                        .select(Users.supporterTier)
                        .where { Users.id eq userId }
                        .singleOrNull()
                        ?.get(Users.supporterTier)
                },
            )
        val purchase = store.find(userId)?.takeIf { it.entitledAt(clock.instant()) }
        val source =
            when {
                purchase != null -> "billing"
                tier != SupporterTier.NONE -> "granted"
                config.selfHosted -> "self_hosted"
                else -> "none"
            }
        return EntitlementResponse(
            entitled = tier != SupporterTier.NONE || config.selfHosted,
            tier = tier.value,
            source = source,
            expiresAt = purchase?.expiresAt?.let(DateTimeFormatter.ISO_INSTANT::format),
            autoRenewing = purchase?.autoRenewing == true,
            billingAccountId = googlePlay?.let { accountId(userId) },
        )
    }

    /** `POST /v1/billing/verify`. */
    suspend fun verify(
        userId: UUID,
        request: VerifyPurchaseRequest,
    ): EntitlementResponse {
        val (play, playConfig) = configured()
        requireValid(request.productId == playConfig.productId) { BillingProblems.unknownProduct() }
        requireValid(TOKEN.matches(request.purchaseToken)) { BillingProblems.invalidPurchase() }
        enforce(rateLimiter.hit(VERIFY_LIMIT, userId.toString()), "Too many purchase checks")
        val purchase = fetch(play, request.purchaseToken)
        requireValid(purchase.obfuscatedAccountId == accountId(userId)) { BillingProblems.otherAccount() }
        val owner = store.ownerOf(request.purchaseToken)
        requireValid(owner == null || owner == userId) { BillingProblems.otherAccount() }
        val record = recordOf(request.purchaseToken, purchase, playConfig.productId, clock.instant())
        requireValid(record != null) { BillingProblems.unknownProduct() }
        val now = clock.instant()
        val current = store.find(userId)
        // A stale purchase (an expired one sent again) never replaces one that still gives access, unless it is its successor.
        val replaces =
            current == null || current.purchaseToken == record?.purchaseToken || !current.entitledAt(now) ||
                record?.entitledAt(now) == true || purchase.linkedPurchaseToken == current.purchaseToken
        if (record != null && replaces) {
            store.save(userId, record, now)
            acknowledgeIfNeeded(play, userId, record)
        }
        return entitlements(userId)
    }

    /** Asks Google again about purchases whose access is about to end or just ended; then syncs every Play user's tier. */
    suspend fun recheck(): BillingRecheckResult {
        var checked = 0
        var failed = 0
        val play = play
        val playConfig = googlePlay
        if (play != null && playConfig != null) {
            store.dueForRecheck(clock.instant(), RECHECK_BATCH).forEach { (userId, token) ->
                if (recheckOne(play, playConfig, userId, token)) checked++ else failed++
            }
        }
        return BillingRecheckResult(checked, failed, store.syncTiers(clock.instant()))
    }

    private suspend fun recheckOne(
        play: PlaySubscriptions,
        playConfig: GooglePlayConfig,
        userId: UUID,
        token: String,
    ): Boolean =
        try {
            play.subscription(token)?.let { recordOf(token, it, playConfig.productId, clock.instant()) }?.let { record ->
                store.save(userId, record, clock.instant())
                acknowledgeIfNeeded(play, userId, record)
            }
            true
        } catch (e: PlayApiException) {
            log.warn("Billing re-check failed for one purchase: {}", e.message)
            false
        }

    private fun configured(): Pair<PlaySubscriptions, GooglePlayConfig> {
        val play = play
        val playConfig = googlePlay
        if (play == null || playConfig == null) throw BillingProblems.notConfigured()
        return play to playConfig
    }

    /** The obfuscated account ID for [userId]: HMAC-SHA256 with the server's billing secret, hex (64 characters, Play's limit). */
    fun accountId(userId: UUID): String {
        val secret = checkNotNull(config.googlePlay) { "Play billing is not configured" }.accountSecret
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), HMAC))
        return mac.doFinal("opencaller-billing:$userId".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private suspend fun fetch(
        play: PlaySubscriptions,
        token: String,
    ): PlaySubscription {
        val purchase =
            try {
                play.subscription(token)
            } catch (e: PlayApiException) {
                throw BillingProblems.unavailable(e)
            }
        return purchase ?: throw BillingProblems.invalidPurchase()
    }

    // Acknowledging is retried by the next check if it fails; the purchase is valid either way.
    private suspend fun acknowledgeIfNeeded(
        play: PlaySubscriptions,
        userId: UUID,
        record: SubscriptionRecord,
    ) {
        if (record.acknowledged || record.state !in PlayStates.PAID) return
        try {
            play.acknowledge(record.productId, record.purchaseToken)
            store.markAcknowledged(userId)
        } catch (e: PlayApiException) {
            log.warn("Acknowledging a purchase failed: {}", e.message)
        }
    }

    private companion object {
        const val HMAC = "HmacSHA256"
        const val RECHECK_BATCH = 500

        // Play purchase tokens are opaque; accept URL-safe text up to 2 KB.
        val TOKEN = Regex("[A-Za-z0-9._:\\-]{1,2048}")
        val VERIFY_LIMIT = RateLimit("billing-verify", maxHits = 30, window = 1.hours)
        val log = LoggerFactory.getLogger(BillingService::class.java)
    }
}

/** Google's subscription states that matter here. */
internal object PlayStates {
    /** Paid and giving access (acknowledged when still pending). */
    val PAID = setOf("SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD")

    /** Canceled: access lasts until the paid period ends. */
    const val CANCELED = "SUBSCRIPTION_STATE_CANCELED"
}

private val RENEWAL_SLACK: java.time.Duration = java.time.Duration.ofHours(2)

/** The stored form of [purchase], or `null` if it isn't [productId] with a known base plan. */
internal fun recordOf(
    token: String,
    purchase: PlaySubscription,
    productId: String,
    now: Instant,
): SubscriptionRecord? =
    purchase.lineItems.firstOrNull { it.productId == productId }?.let { item ->
        PaidTier.ofBasePlan(item.basePlanId)?.let { tier ->
            SubscriptionRecord(
                purchaseToken = token,
                productId = productId,
                basePlanId = item.basePlanId.orEmpty(),
                tier = tier,
                state = purchase.state,
                expiresAt = item.expiryTime,
                entitledUntil = entitledUntil(purchase.state, item.expiryTime, now),
                autoRenewing = item.autoRenewing,
                acknowledged = purchase.acknowledged,
                testPurchase = purchase.testPurchase,
            )
        }
    }

// Active and grace-period subscriptions keep access a little past expiryTime, so a renewal Google hasn't reported
// yet doesn't lock anyone out; a canceled one keeps it until the paid period ends; anything else has none.
private fun entitledUntil(
    state: String,
    expiry: Instant?,
    now: Instant,
): Instant? =
    when (state) {
        in PlayStates.PAID -> (expiry ?: now).plus(RENEWAL_SLACK)
        PlayStates.CANCELED -> expiry
        else -> null
    }
