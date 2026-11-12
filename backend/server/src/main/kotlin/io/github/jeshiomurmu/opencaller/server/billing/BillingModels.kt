package io.github.jeshiomurmu.opencaller.server.billing

import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/** `POST /v1/billing/verify` body: a Play purchase of the supporter subscription. */
@Serializable
data class VerifyPurchaseRequest(
    val productId: String,
    val purchaseToken: String,
)

/**
 * `GET /v1/entitlements` and `POST /v1/billing/verify` response. [entitled] unlocks supporter features; [tier] is
 * the paid (or granted) tier, `none` on a self-hosted server without one; [source] is `billing`, `granted` (set by
 * the operator), `self_hosted` or `none`. [billingAccountId] is what the app passes to Play as the obfuscated
 * account ID (only while Play billing is configured).
 */
@Serializable
data class EntitlementResponse(
    val entitled: Boolean,
    val tier: String,
    val source: String,
    val expiresAt: String? = null,
    val autoRenewing: Boolean = false,
    val billingAccountId: String? = null,
)

/** `GET /v1/server`: what this server offers, for apps before sign-in. */
@Serializable
data class ServerInfoResponse(
    val selfHosted: Boolean,
    val billing: BillingInfo,
    val features: FeatureInfo = FeatureInfo(),
)

/**
 * Phase 11 features of a server: [appCalls] (LiveKit and a push provider configured), [flash] (a push provider),
 * [fcm] (play builds can register FCM tokens) and [webPushVapidKey] (foss builds pass it to their UnifiedPush
 * distributor; `null` when Web Push is off); Phase 13: [assistant] (the call assistant has numbers and LiveKit).
 */
@Serializable
data class FeatureInfo(
    val appCalls: Boolean = false,
    val flash: Boolean = false,
    val fcm: Boolean = false,
    val webPushVapidKey: String? = null,
    val assistant: Boolean = false,
)

/** Purchase options of a server; [googlePlayProductId] is set while Play billing is configured. */
@Serializable
data class BillingInfo(
    val googlePlayProductId: String? = null,
)

/** What one billing re-check run did. */
@Serializable
data class BillingRecheckResult(
    val checked: Int,
    val failed: Int,
    val tiersChanged: Int,
)

/** Paid tiers, from the base plan ID's first part (`gold`, `gold-yearly` → [GOLD]). */
enum class PaidTier(
    val value: String,
) {
    SUPPORTER("supporter"),
    GOLD("gold"),
    FAMILY("family"),
    ;

    /** Lookup by base plan. */
    companion object {
        /** The tier of [basePlanId], or `null` if it names none. */
        fun ofBasePlan(basePlanId: String?): PaidTier? {
            val first = basePlanId?.lowercase()?.substringBefore('-') ?: return null
            return entries.firstOrNull { it.value == first }
        }
    }
}

/** Billing problems (problem+json codes). */
internal object BillingProblems {
    fun notConfigured() =
        ApiProblem(
            HttpStatusCode.ServiceUnavailable,
            "billing_not_configured",
            "Purchases can't be verified on this server",
            "This server has no Google Play billing set up.",
        )

    fun invalidPurchase() = ApiProblem(HttpStatusCode.BadRequest, "invalid_purchase", "Google Play doesn't know this purchase")

    fun unknownProduct() =
        ApiProblem(
            HttpStatusCode.BadRequest,
            "unknown_product",
            "Not a supporter subscription",
            "The purchase is not the supporter subscription or has an unknown base plan.",
        )

    fun otherAccount() =
        ApiProblem(
            HttpStatusCode.Conflict,
            "purchase_other_account",
            "This purchase belongs to another account",
            "Sign in with the number that made the purchase.",
        )

    fun unavailable(cause: Throwable) =
        ApiProblem(HttpStatusCode.BadGateway, "billing_unavailable", "Google Play can't be reached right now", cause = cause)
}
