package io.github.jeshiomurmu.opencaller.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.supporter.PurchaseOutcome
import io.github.jeshiomurmu.opencaller.core.data.supporter.SupporterStore
import io.github.jeshiomurmu.opencaller.core.model.PaidTier
import io.github.jeshiomurmu.opencaller.core.model.SupporterPlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The supporter subscription through Google Play Billing (Play Billing Library 9.1, callback API as in Google's
 * "Integrate the Google Play Billing Library" guide): the subscription's base plans with Play's prices, the purchase
 * dialog with the account's obfuscated ID, and the store account's purchases. The server verifies and acknowledges
 * every purchase (`POST /v1/billing/verify`); this class never grants anything itself.
 */
@Singleton
class PlaySupporterStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : SupporterStore,
        PurchasesUpdatedListener {
        private val client: BillingClient =
            BillingClient
                .newBuilder(context)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build()
        private val connecting = Mutex()
        private val purchaseLock = Mutex()
        private var details: ProductDetails? = null
        private var waiting: CompletableDeferred<Pair<BillingResult, List<Purchase>?>>? = null

        override val available: Boolean = true

        override val donationUrl: String? = null

        override suspend fun plans(productId: String): AppResult<List<SupporterPlan>> {
            val product = productDetails(productId)
            if (product !is AppResult.Success) return AppResult.Failure((product as AppResult.Failure).error)
            // Base plans only (offerId == null); their last pricing phase is the recurring price.
            val plans =
                product.value.subscriptionOfferDetails.orEmpty().filter { it.offerId == null }.mapNotNull { offer ->
                    val tier = tierOf(offer.basePlanId) ?: return@mapNotNull null
                    val phase = offer.pricingPhases.pricingPhaseList.lastOrNull() ?: return@mapNotNull null
                    SupporterPlan(tier, offer.basePlanId, phase.formattedPrice, phase.billingPeriod, offer.offerToken)
                }
            return AppResult.Success(plans.sortedBy { it.tier.ordinal })
        }

        override suspend fun buy(
            activity: Activity,
            productId: String,
            plan: SupporterPlan,
            accountId: String,
        ): AppResult<PurchaseOutcome> =
            purchaseLock.withLock {
                val product = productDetails(productId)
                if (product !is AppResult.Success) return@withLock AppResult.Failure((product as AppResult.Failure).error)
                val params =
                    BillingFlowParams
                        .newBuilder()
                        .setProductDetailsParamsList(
                            listOf(
                                BillingFlowParams.ProductDetailsParams
                                    .newBuilder()
                                    .setProductDetails(product.value)
                                    .setOfferToken(plan.offerToken)
                                    .build(),
                            ),
                        ).setObfuscatedAccountId(accountId)
                        .build()
                val result = CompletableDeferred<Pair<BillingResult, List<Purchase>?>>()
                waiting = result
                val launched = withContext(Dispatchers.Main) { client.launchBillingFlow(activity, params) }
                if (launched.responseCode != BillingClient.BillingResponseCode.OK) {
                    waiting = null
                    return@withLock failure("launchBillingFlow", launched)
                }
                val (billing, purchases) = result.await()
                outcomeOf(productId, billing, purchases)
            }

        override suspend fun ownedPurchases(productId: String): AppResult<List<String>> {
            if (!connect()) return AppResult.Failure(AppError.Network)
            val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
            val (result, purchases) =
                suspendCancellableCoroutine { cont ->
                    client.queryPurchasesAsync(params) { billing, list -> cont.resume(billing to list) }
                }
            return if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                AppResult.Success(
                    purchases
                        .filter { productId in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
                        .map { it.purchaseToken },
                )
            } else {
                failure("queryPurchasesAsync", result)
            }
        }

        override fun onPurchasesUpdated(
            result: BillingResult,
            purchases: List<Purchase>?,
        ) {
            val pending = waiting
            if (pending != null) {
                waiting = null
                pending.complete(result to purchases)
            } else {
                // A pending payment completed, or a purchase made outside the app: the next refresh sends it to the server.
                Timber.i("Purchases updated outside a purchase flow (code %d)", result.responseCode)
            }
        }

        private fun outcomeOf(
            productId: String,
            result: BillingResult,
            purchases: List<Purchase>?,
        ): AppResult<PurchaseOutcome> {
            val purchase = purchases.orEmpty().firstOrNull { productId in it.products }
            return when {
                result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED -> AppResult.Success(PurchaseOutcome.Cancelled)
                result.responseCode != BillingClient.BillingResponseCode.OK || purchase == null -> failure("purchase", result)
                purchase.purchaseState == Purchase.PurchaseState.PENDING -> AppResult.Success(PurchaseOutcome.Pending)
                else -> AppResult.Success(PurchaseOutcome.Purchased(purchase.purchaseToken))
            }
        }

        private suspend fun productDetails(productId: String): AppResult<ProductDetails> {
            val cached = details?.takeIf { it.productId == productId }
            return when {
                cached != null -> AppResult.Success(cached)
                !connect() -> AppResult.Failure(AppError.Network)
                else -> queryProductDetails(productId)
            }
        }

        private suspend fun queryProductDetails(productId: String): AppResult<ProductDetails> {
            val params =
                QueryProductDetailsParams
                    .newBuilder()
                    .setProductList(
                        listOf(
                            QueryProductDetailsParams.Product
                                .newBuilder()
                                .setProductId(productId)
                                .setProductType(BillingClient.ProductType.SUBS)
                                .build(),
                        ),
                    ).build()
            val (result, found) =
                suspendCancellableCoroutine { cont ->
                    client.queryProductDetailsAsync(
                        params,
                    ) { billing, list -> cont.resume(billing to list.productDetailsList.firstOrNull()) }
                }
            return when {
                result.responseCode != BillingClient.BillingResponseCode.OK -> failure("queryProductDetailsAsync", result)
                found == null -> AppResult.Failure(AppError.NotFound)
                else -> AppResult.Success(found.also { details = it })
            }
        }

        // The first call connects; with automatic reconnection on, later calls reconnect inside the library.
        private suspend fun connect(): Boolean =
            connecting.withLock {
                if (client.isReady) return@withLock true
                suspendCancellableCoroutine { cont ->
                    client.startConnection(
                        object : BillingClientStateListener {
                            override fun onBillingSetupFinished(result: BillingResult) {
                                if (cont.isActive) cont.resume(result.responseCode == BillingClient.BillingResponseCode.OK)
                            }

                            override fun onBillingServiceDisconnected() {
                                if (cont.isActive) cont.resume(false)
                            }
                        },
                    )
                }
            }

        private fun failure(
            call: String,
            result: BillingResult,
        ): AppResult.Failure {
            Timber.w("Play Billing %s failed: code %d", call, result.responseCode)
            return AppResult.Failure(
                when (result.responseCode) {
                    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                    BillingClient.BillingResponseCode.NETWORK_ERROR,
                    -> AppError.Network

                    else -> AppError.Validation("billing_${result.responseCode}")
                },
            )
        }

        private fun tierOf(basePlanId: String): PaidTier? =
            PaidTier.entries.firstOrNull {
                it.name.equals(basePlanId.substringBefore('-'), ignoreCase = true)
            }
    }
