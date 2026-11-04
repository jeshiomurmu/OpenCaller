package io.github.jeshiomurmu.opencaller.core.network

import kotlinx.serialization.Serializable

/** `GET /v1/server` response. */
@Serializable
data class ServerInfoDto(
    val selfHosted: Boolean = false,
    val billing: BillingInfoDto = BillingInfoDto(),
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
