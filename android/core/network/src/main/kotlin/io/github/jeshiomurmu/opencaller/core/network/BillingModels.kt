package io.github.jeshiomurmu.opencaller.core.network

import kotlinx.serialization.Serializable

/** `GET /v1/server` response. */
@Serializable
data class ServerInfoDto(
    val selfHosted: Boolean = false,
    val billing: BillingInfoDto = BillingInfoDto(),
    val features: FeaturesDto = FeaturesDto(),
)

/**
 * Phase 11 features of the server: app calls (LiveKit and a push provider), Flash messages (a push provider), FCM,
 * and the VAPID key a UnifiedPush registration needs. Servers before Phase 11 send nothing: everything off.
 */
@Serializable
data class FeaturesDto(
    val appCalls: Boolean = false,
    val flash: Boolean = false,
    val fcm: Boolean = false,
    val webPushVapidKey: String? = null,
)

/** Purchase options of the server; [googlePlayProductId] is set while Play billing is configured. */
@Serializable
data class BillingInfoDto(
    val googlePlayProductId: String? = null,
)

/** `GET /v1/entitlements` and `POST /v1/billing/verify` response. */
@Serializable
data class EntitlementDto(
    val entitled: Boolean,
    val tier: String,
    val source: String,
    val expiresAt: String? = null,
    val autoRenewing: Boolean = false,
    val billingAccountId: String? = null,
)

/** `POST /v1/billing/verify` body. */
@Serializable
data class VerifyPurchaseDto(
    val productId: String,
    val purchaseToken: String,
)
