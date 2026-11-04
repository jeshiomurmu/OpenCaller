package io.github.jeshiomurmu.opencaller.core.data.supporter

import android.app.Activity
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.Entitlement
import io.github.jeshiomurmu.opencaller.core.model.SupporterPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * What the user may use of the supporter features (docs/architecture.md §3: `EntitlementProvider`). The answer
 * comes from the server (a verified purchase, a tier the operator granted, or a self-hosted server) and is kept on
 * the phone, so features work offline until the paid period ends.
 */
interface EntitlementProvider {
    /** The current entitlement, emitting again when it changes. */
    val entitlement: Flow<Entitlement>

    /** What the server offers, emitting again when it changes. */
    val offer: Flow<SupporterOffer>

    /** The current entitlement (from the phone's copy; no network). */
    suspend fun current(): Entitlement

    /** Asks the server again (and, in the play build, re-sends the store's purchases). */
    suspend fun refresh(): AppResult<Entitlement>

    /** Has the server verify a store purchase of [productId]. */
    suspend fun verifyPurchase(
        productId: String,
        purchaseToken: String,
    ): AppResult<Entitlement>
}

/**
 * What the server offers. [selfHosted]: every supporter feature is unlocked for everyone. [productId]: the Play
 * subscription it verifies (play build with billing configured). [billingAccountId]: the signed-in user's
 * obfuscated account ID for a purchase.
 */
data class SupporterOffer(
    val serverConfigured: Boolean = false,
    val selfHosted: Boolean = false,
    val productId: String? = null,
    val billingAccountId: String? = null,
)

/** How a purchase ended in the store's own dialog. */
sealed interface PurchaseOutcome {
    /** Paid; [purchaseToken] goes to the server to be verified. */
    data class Purchased(
        val purchaseToken: String,
    ) : PurchaseOutcome

    /** The payment isn't complete yet (e.g. cash at a store); the purchase arrives later. */
    data object Pending : PurchaseOutcome

    /** The user closed the dialog. */
    data object Cancelled : PurchaseOutcome
}

/**
 * The store's side of buying the subscription (docs/architecture.md §3): Google Play Billing in the play build,
 * nothing in the foss build (which links to donations instead).
 */
interface SupporterStore {
    /** `true` if this build can buy in a store. */
    val available: Boolean

    /** Where to donate (foss build), from the build's configuration; `null` for none. */
    val donationUrl: String?

    /** The plans of subscription [productId], with the store's prices. */
    suspend fun plans(productId: String): AppResult<List<SupporterPlan>>

    /** Shows the store's purchase dialog for [plan] over [activity], bound to [accountId]. */
    suspend fun buy(
        activity: Activity,
        productId: String,
        plan: SupporterPlan,
        accountId: String,
    ): AppResult<PurchaseOutcome>

    /** Purchase tokens of [productId] the store account owns (to restore on a new phone). */
    suspend fun ownedPurchases(productId: String): AppResult<List<String>>
}

/** A fixed [EntitlementProvider] for tests and previews (and builds without a server). */
class StaticEntitlements(
    private val value: Entitlement = Entitlement.NONE,
    private val supporterOffer: SupporterOffer = SupporterOffer(),
) : EntitlementProvider {
    override val entitlement: Flow<Entitlement> = flowOf(value)

    override val offer: Flow<SupporterOffer> = flowOf(supporterOffer)

    override suspend fun current(): Entitlement = value

    override suspend fun refresh(): AppResult<Entitlement> = AppResult.Success(value)

    override suspend fun verifyPurchase(
        productId: String,
        purchaseToken: String,
    ): AppResult<Entitlement> = AppResult.Success(value)
}
