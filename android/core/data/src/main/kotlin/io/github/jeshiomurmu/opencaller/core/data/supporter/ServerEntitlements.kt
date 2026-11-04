package io.github.jeshiomurmu.opencaller.core.data.supporter

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.account.AccountStatus
import io.github.jeshiomurmu.opencaller.core.model.Entitlement
import io.github.jeshiomurmu.opencaller.core.model.EntitlementSource
import io.github.jeshiomurmu.opencaller.core.model.PaidTier
import io.github.jeshiomurmu.opencaller.core.network.BillingApi
import io.github.jeshiomurmu.opencaller.core.network.EntitlementDto
import io.github.jeshiomurmu.opencaller.core.network.ServerInfoDto
import io.github.jeshiomurmu.opencaller.core.network.VerifyPurchaseDto
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [EntitlementProvider] backed by the server (`GET /v1/server`, `GET /v1/entitlements`, `POST /v1/billing/verify`)
 * and the phone's copy of its last answer. Offline, an entitlement lasts until its paid period ends plus
 * [OFFLINE_GRACE_MILLIS] (a purchase) or [OFFLINE_GRACE_MILLIS] after the last answer (a granted tier, a self-hosted
 * server). Without a server nothing is unlocked.
 */
@Singleton
class ServerEntitlements
    @Inject
    constructor(
        private val api: BillingApi,
        private val account: AccountStatus,
        private val store: EntitlementStore,
        private val purchases: SupporterStore,
        private val clock: Clock,
        private val dispatchers: DispatcherProvider,
    ) : EntitlementProvider {
        override val entitlement: Flow<Entitlement> = store.stored.map(::effective).distinctUntilChanged()

        override val offer: Flow<SupporterOffer> = store.stored.map { it.offer }.distinctUntilChanged()

        override suspend fun current(): Entitlement = effective(store.stored.first())

        override suspend fun refresh(): AppResult<Entitlement> =
            withContext(dispatchers.io) {
                if (!account.serverConfigured) {
                    store.save(StoredEntitlement(Entitlement.NONE, SupporterOffer(), clock.nowMillis()))
                    return@withContext AppResult.Success(Entitlement.NONE)
                }
                when (val info = apiCall { api.serverInfo() }) {
                    is AppResult.Failure -> AppResult.Failure(info.error)
                    is AppResult.Success -> refreshWith(info.value)
                }
            }

        override suspend fun verifyPurchase(
            productId: String,
            purchaseToken: String,
        ): AppResult<Entitlement> =
            withContext(dispatchers.io) {
                when (val result = apiCall { api.verifyPurchase(VerifyPurchaseDto(productId, purchaseToken)) }) {
                    is AppResult.Failure -> AppResult.Failure(result.error)
                    is AppResult.Success -> AppResult.Success(saveAnswer(result.value, store.stored.first().offer))
                }
            }

        private suspend fun refreshWith(info: ServerInfoDto): AppResult<Entitlement> {
            val offer = SupporterOffer(serverConfigured = true, selfHosted = info.selfHosted, productId = info.billing.googlePlayProductId)
            if (!account.signedIn.first()) {
                val entitlement = if (info.selfHosted) SELF_HOSTED else Entitlement.NONE
                store.save(StoredEntitlement(entitlement, offer, clock.nowMillis()))
                return AppResult.Success(entitlement)
            }
            return when (val answer = apiCall { api.entitlements() }) {
                is AppResult.Failure -> {
                    AppResult.Failure(answer.error)
                }

                is AppResult.Success -> {
                    val saved = saveAnswer(answer.value, offer)
                    resendStorePurchases(offer.productId, saved)
                }
            }
        }

        // A purchase made on another phone (or before a reinstall) is in the store account but not yet known to this
        // install's server account: send the store's purchases so the server can bind them (it refuses other accounts').
        private suspend fun resendStorePurchases(
            productId: String?,
            entitlement: Entitlement,
        ): AppResult<Entitlement> {
            if (entitlement.source == EntitlementSource.BILLING || productId == null || !purchases.available) {
                return AppResult.Success(entitlement)
            }
            val tokens = (purchases.ownedPurchases(productId) as? AppResult.Success)?.value.orEmpty()
            var latest = entitlement
            tokens.forEach { token ->
                when (val verified = verifyPurchase(productId, token)) {
                    is AppResult.Success -> latest = verified.value
                    is AppResult.Failure -> Timber.w("A store purchase was not accepted: %s", verified.error)
                }
            }
            return AppResult.Success(latest)
        }

        private suspend fun saveAnswer(
            dto: EntitlementDto,
            offer: SupporterOffer,
        ): Entitlement {
            val entitlement = dto.toEntitlement()
            store.save(StoredEntitlement(entitlement, offer.copy(billingAccountId = dto.billingAccountId), clock.nowMillis()))
            return entitlement
        }

        private fun effective(stored: StoredEntitlement): Entitlement {
            val e = stored.entitlement
            if (!e.entitled) return Entitlement.NONE
            val now = clock.nowMillis()
            val expiresAt = e.expiresAtMillis
            val until =
                when {
                    e.source == EntitlementSource.BILLING && expiresAt != null -> expiresAt + OFFLINE_GRACE_MILLIS
                    else -> (stored.checkedAtMillis ?: 0L) + OFFLINE_GRACE_MILLIS
                }
            return if (now < until) e else Entitlement.NONE
        }

        /** Offline limits. */
        companion object {
            /** How long an entitlement holds without a fresh answer from the server: 7 days. */
            const val OFFLINE_GRACE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

            private val SELF_HOSTED = Entitlement(entitled = true, tier = null, source = EntitlementSource.SELF_HOSTED)
        }
    }

/** The domain form of the server's answer; unknown values count as none. */
internal fun EntitlementDto.toEntitlement(): Entitlement =
    Entitlement(
        entitled = entitled,
        tier = PaidTier.entries.firstOrNull { it.name.equals(tier, ignoreCase = true) },
        source = EntitlementSource.entries.firstOrNull { it.name.equals(source, ignoreCase = true) } ?: EntitlementSource.NONE,
        expiresAtMillis =
            expiresAt?.let {
                try {
                    Instant.parse(it).toEpochMilli()
                } catch (e: DateTimeParseException) {
                    Timber.w(e, "Unreadable expiresAt from the server")
                    null
                }
            },
        autoRenewing = autoRenewing,
    )

/** No store in this build: nothing to buy or restore (foss, or a test). */
class NoSupporterStore(
    override val donationUrl: String?,
) : SupporterStore {
    override val available: Boolean = false

    override suspend fun plans(productId: String): AppResult<List<io.github.jeshiomurmu.opencaller.core.model.SupporterPlan>> =
        AppResult.Failure(AppError.Validation("no_store"))

    override suspend fun buy(
        activity: android.app.Activity,
        productId: String,
        plan: io.github.jeshiomurmu.opencaller.core.model.SupporterPlan,
        accountId: String,
    ): AppResult<PurchaseOutcome> = AppResult.Failure(AppError.Validation("no_store"))

    override suspend fun ownedPurchases(productId: String): AppResult<List<String>> = AppResult.Success(emptyList())
}
