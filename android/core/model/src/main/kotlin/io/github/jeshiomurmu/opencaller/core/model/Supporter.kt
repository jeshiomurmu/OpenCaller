package io.github.jeshiomurmu.opencaller.core.model

/** Paid supporter tiers (docs/prd.md F-UT-08), one base plan each in the Play subscription. */
enum class PaidTier {
    /** Supporter. */
    SUPPORTER,

    /** Gold. */
    GOLD,

    /** Family (shared with up to five people once family groups exist, Phase 12). */
    FAMILY,
}

/** Why supporter features are on (or not). */
enum class EntitlementSource {
    /** No entitlement. */
    NONE,

    /** A verified Play purchase. */
    BILLING,

    /** Granted by the server's operator. */
    GRANTED,

    /** The server is self-hosted: everything is unlocked for everyone. */
    SELF_HOSTED,
}

/**
 * What the user may use of the supporter features: Ghost Call, Announce Caller, creating backups, messenger caller
 * ID, the extended spam list and the Gold theme (owner's decision, 2026-11-03). Restoring a backup is always free.
 *
 * @property tier the paid or granted tier, `null` for none (a self-hosted server unlocks without one).
 * @property expiresAtMillis end of the paid period of an active purchase, if known.
 */
data class Entitlement(
    val entitled: Boolean,
    val tier: PaidTier?,
    val source: EntitlementSource,
    val expiresAtMillis: Long? = null,
    val autoRenewing: Boolean = false,
) {
    /** The no-entitlement value. */
    companion object {
        /** Nothing unlocked. */
        val NONE: Entitlement = Entitlement(entitled = false, tier = null, source = EntitlementSource.NONE)
    }
}

/**
 * A supporter plan offered by the store, ready to buy.
 *
 * @property basePlanId the Play base plan (`supporter`, `gold-yearly`, …).
 * @property price the localized price text from the store, e.g. "€2.99/month" (never formatted by OpenCaller).
 * @property offerToken the store's token for the offer to buy.
 */
data class SupporterPlan(
    val tier: PaidTier,
    val basePlanId: String,
    val price: String,
    val billingPeriod: String,
    val offerToken: String,
)

/** The fake incoming call a user sets up (docs/prd.md F-UT-01). Nothing about it is real: no call is placed. */
data class GhostCallSetup(
    val callerName: String = "",
    /** Shown as typed: it's a made-up number on a made-up call, never dialled or looked up. */
    val displayNumber: String = "",
    val delaySeconds: Int = DEFAULT_DELAY_SECONDS,
    val hasPhoto: Boolean = false,
) {
    /** Delays the screen offers. */
    companion object {
        /** Default delay. */
        const val DEFAULT_DELAY_SECONDS: Int = 30

        /** Delays offered, in seconds. */
        val DELAYS: List<Int> = listOf(10, 30, 60, 300, 900)

        /** Longest caller name. */
        const val MAX_NAME_LENGTH: Int = 40

        /** Longest number text. */
        const val MAX_NUMBER_LENGTH: Int = 20
    }
}
