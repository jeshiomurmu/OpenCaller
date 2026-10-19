package io.github.jeshiomurmu.opencaller.core.model

private const val LOW_THRESHOLD = 0.4f
private const val MEDIUM_THRESHOLD = 0.6f
private const val HIGH_THRESHOLD = 0.85f

/**
 * The spam score from which a listed number is blocked (docs/prd.md, Phase 3). A *higher* threshold needs a
 * stronger signal, so it blocks fewer calls: [HIGH] blocks only near-certain spam, [LOW] blocks more and risks
 * more false positives. Numbers listed below the threshold still show as "Suspicious" on the caller card when
 * their score is at least [SpamVerdict.SUSPICIOUS_MIN_SCORE].
 *
 * @property minimumScore spam score (`0.0..1.0`) at or above which a listed number is blocked.
 */
enum class SpamThreshold(
    val minimumScore: Float,
) {
    /** Block at a score of 0.4 or more: the most calls blocked. */
    LOW(LOW_THRESHOLD),

    /** Block at 0.6 or more: the default balance between missed spam and false positives. */
    MEDIUM(MEDIUM_THRESHOLD),

    /** Block only at 0.85 or more: near-certain spam. */
    HIGH(HIGH_THRESHOLD),
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

/**
 * Steps of the onboarding flow, in order. The user sees at most five numbered steps: [CONTACTS] and
 * [NOTIFICATIONS] share the "Permissions" step, and the optional roles and overlay share [EXTRAS].
 */
enum class OnboardingStep {
    /** Welcome screen. */
    WELCOME,

    /** What leaves the phone and what never does. */
    PRIVACY,

    /** Explainer, then the caller ID & spam (call screening) role request. */
    CALL_SCREENING,

    /** Explainer, then the contacts permission request. */
    CONTACTS,

    /** Explainer, then the notification permission request (Android 13+; skipped below). */
    NOTIFICATIONS,

    /** Optional: default phone app, default SMS app, caller ID over other apps. */
    EXTRAS,

    /** Onboarding finished. */
    DONE,
}

/**
 * The user's settings (stored by `core:datastore`).
 *
 * @property allowedRegionCodes ISO 3166-1 alpha-2 regions that are never treated as foreign.
 * @property listUpdatesOnMobileData whether the daily spam-list update may use mobile data (default: Wi-Fi or other
 *   unmetered networks only).
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
    val listUpdatesOnMobileData: Boolean = false,
)
