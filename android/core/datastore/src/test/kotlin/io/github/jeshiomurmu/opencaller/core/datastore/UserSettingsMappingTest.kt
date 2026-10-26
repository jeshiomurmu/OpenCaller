package io.github.jeshiomurmu.opencaller.core.datastore

import io.github.jeshiomurmu.opencaller.core.model.AnnounceCallerMode
import io.github.jeshiomurmu.opencaller.core.model.OnboardingStep
import io.github.jeshiomurmu.opencaller.core.model.SpamThreshold
import io.github.jeshiomurmu.opencaller.core.model.ThemeMode
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class UserSettingsMappingTest {
    @Test
    fun emptyProtoMapsToDefaults() {
        assertEquals(UserPreferences(), UserSettings.getDefaultInstance().toDomain())
    }

    @Test
    fun everyEnumValueRoundTrips() {
        SpamThreshold.entries.forEach { assertRoundTrip(UserPreferences(spamThreshold = it)) }
        AnnounceCallerMode.entries.forEach { assertRoundTrip(UserPreferences(announceCallerMode = it)) }
        ThemeMode.entries.forEach { assertRoundTrip(UserPreferences(themeMode = it)) }
        OnboardingStep.entries.forEach { assertRoundTrip(UserPreferences(onboardingStep = it)) }
    }

    @Test
    fun togglesAndRegionsRoundTrip() {
        assertRoundTrip(
            UserPreferences(
                blockHiddenNumbers = true,
                blockForeignNumbers = true,
                blockNonContacts = true,
                blockTopSpammers = false,
                allowedRegionCodes = setOf("IN", "BD"),
                overlayEnabled = false,
                listUpdatesOnMobileData = true,
            ),
        )
    }

    @Test
    fun listUpdatesUseUnmeteredNetworksByDefault() {
        assertEquals(false, UserSettings.getDefaultInstance().toDomain().listUpdatesOnMobileData)
    }

    @Test
    fun quickRepliesKeepTheirOrder() {
        assertRoundTrip(UserPreferences(quickReplies = listOf("Can't talk now", "Call you back in 5", "Can't talk now")))
        assertEquals(emptyList<String>(), UserSettings.getDefaultInstance().toDomain().quickReplies)
    }

    @Test
    fun unrecognizedEnumFallsBackToDefault() {
        val stored = UserSettings.newBuilder().setThemeModeValue(UNKNOWN_ENUM_NUMBER).build()
        assertEquals(ThemeMode.SYSTEM, stored.toDomain().themeMode)
    }

    private fun assertRoundTrip(preferences: UserPreferences) {
        val bytes = preferences.toProto().toByteArray()
        assertEquals(preferences, UserSettings.parseFrom(bytes).toDomain())
    }

    private companion object {
        const val UNKNOWN_ENUM_NUMBER = 42
    }
}
