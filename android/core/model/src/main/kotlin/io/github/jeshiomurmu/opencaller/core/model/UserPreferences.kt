package io.github.jeshiomurmu.opencaller.core.model

/** How aggressively spam is blocked. */
enum class SpamThreshold {
    /** Block only numbers with very strong spam signals. */
    LOW,

    /** Default balance between missed spam and false positives. */
    MEDIUM,

    /** Block on weaker signals too. */
    HIGH,
}

/** When the caller's name is announced aloud. */
enum class AnnounceCallerMode {
    /** Never announce. */
    OFF,

    /** Announce for every incoming call. */
    ALWAYS,

    /** Announce only while headphones are connected. */
    HEADSET_ONLY,
}

/** App color theme. */
enum class ThemeMode {
    /** Follow the system setting. */
    SYSTEM,

    /** Always light. */
    LIGHT,

    /** Always dark. */
    DARK,
}

/** Steps of the onboarding flow, in order. */
enum class OnboardingStep {
    /** Welcome screen. */
    WELCOME,

    /** Privacy explainer. */
    PRIVACY,

    /** Caller ID & spam role request. */
    ROLES,

    /** Optional sign-up. */
    SIGN_UP,

    /** Onboarding finished. */
    DONE,
}

/**
 * The user's settings (stored by `core:datastore`).
 *
 * @property allowedRegionCodes ISO 3166-1 alpha-2 regions that are never treated as foreign.
 */
data class UserPreferences(
    val blockHiddenNumbers: Boolean = false,
    val blockForeignNumbers: Boolean = false,
    val blockNonContacts: Boolean = false,
    val blockTopSpammers: Boolean = true,
    val spamThreshold: SpamThreshold = SpamThreshold.MEDIUM,
    val allowedRegionCodes: Set<String> = emptySet(),
    val overlayEnabled: Boolean = true,
    val announceCallerMode: AnnounceCallerMode = AnnounceCallerMode.OFF,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val onboardingStep: OnboardingStep = OnboardingStep.WELCOME,
)
