package io.github.jeshiomurmu.opencaller.core.datastore

import io.github.jeshiomurmu.opencaller.core.model.AnnounceCallerMode
import io.github.jeshiomurmu.opencaller.core.model.OnboardingStep
import io.github.jeshiomurmu.opencaller.core.model.SpamThreshold
import io.github.jeshiomurmu.opencaller.core.model.ThemeMode
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences

private val defaults = UserPreferences()

private val spamThresholds =
    mapOf(
        SpamThreshold.LOW to SpamThresholdProto.SPAM_THRESHOLD_LOW,
        SpamThreshold.MEDIUM to SpamThresholdProto.SPAM_THRESHOLD_MEDIUM,
        SpamThreshold.HIGH to SpamThresholdProto.SPAM_THRESHOLD_HIGH,
    )

private val announceModes =
    mapOf(
        AnnounceCallerMode.OFF to AnnounceCallerModeProto.ANNOUNCE_CALLER_MODE_OFF,
        AnnounceCallerMode.ALWAYS to AnnounceCallerModeProto.ANNOUNCE_CALLER_MODE_ALWAYS,
        AnnounceCallerMode.HEADSET_ONLY to AnnounceCallerModeProto.ANNOUNCE_CALLER_MODE_HEADSET_ONLY,
    )

private val themeModes =
    mapOf(
        ThemeMode.SYSTEM to ThemeModeProto.THEME_MODE_SYSTEM,
        ThemeMode.LIGHT to ThemeModeProto.THEME_MODE_LIGHT,
        ThemeMode.DARK to ThemeModeProto.THEME_MODE_DARK,
    )

private val onboardingSteps =
    mapOf(
        OnboardingStep.WELCOME to OnboardingStepProto.ONBOARDING_STEP_WELCOME,
        OnboardingStep.PRIVACY to OnboardingStepProto.ONBOARDING_STEP_PRIVACY,
        OnboardingStep.ROLES to OnboardingStepProto.ONBOARDING_STEP_ROLES,
        OnboardingStep.SIGN_UP to OnboardingStepProto.ONBOARDING_STEP_SIGN_UP,
        OnboardingStep.DONE to OnboardingStepProto.ONBOARDING_STEP_DONE,
    )

private fun <D, P> Map<D, P>.domainOf(
    proto: P,
    default: D,
): D = entries.firstOrNull { it.value == proto }?.key ?: default

/** Maps stored settings to the domain model; unset or unknown values fall back to [UserPreferences] defaults. */
internal fun UserSettings.toDomain(): UserPreferences =
    UserPreferences(
        blockHiddenNumbers = blockHiddenNumbers,
        blockForeignNumbers = blockForeignNumbers,
        blockNonContacts = blockNonContacts,
        blockTopSpammers = if (hasBlockTopSpammers()) blockTopSpammers else defaults.blockTopSpammers,
        spamThreshold = spamThresholds.domainOf(spamThreshold, defaults.spamThreshold),
        allowedRegionCodes = allowedRegionCodesList.toSet(),
        overlayEnabled = if (hasOverlayEnabled()) overlayEnabled else defaults.overlayEnabled,
        announceCallerMode = announceModes.domainOf(announceCallerMode, defaults.announceCallerMode),
        themeMode = themeModes.domainOf(themeMode, defaults.themeMode),
        onboardingStep = onboardingSteps.domainOf(onboardingStep, defaults.onboardingStep),
    )

/** Maps the domain model to its stored form. Region codes are stored sorted so equal sets serialize equally. */
internal fun UserPreferences.toProto(): UserSettings =
    UserSettings
        .newBuilder()
        .setBlockHiddenNumbers(blockHiddenNumbers)
        .setBlockForeignNumbers(blockForeignNumbers)
        .setBlockNonContacts(blockNonContacts)
        .setBlockTopSpammers(blockTopSpammers)
        .setSpamThreshold(spamThresholds.getValue(spamThreshold))
        .addAllAllowedRegionCodes(allowedRegionCodes.sorted())
        .setOverlayEnabled(overlayEnabled)
        .setAnnounceCallerMode(announceModes.getValue(announceCallerMode))
        .setThemeMode(themeModes.getValue(themeMode))
        .setOnboardingStep(onboardingSteps.getValue(onboardingStep))
        .build()
