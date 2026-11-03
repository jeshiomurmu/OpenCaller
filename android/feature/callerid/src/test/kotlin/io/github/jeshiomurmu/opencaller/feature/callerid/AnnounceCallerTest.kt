package io.github.jeshiomurmu.opencaller.feature.callerid

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.data.supporter.StaticEntitlements
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.datastore.inMemory
import io.github.jeshiomurmu.opencaller.core.model.AnnounceCallerMode
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.Entitlement
import io.github.jeshiomurmu.opencaller.core.model.EntitlementSource
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PaidTier
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.SpamVerdict
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import io.github.jeshiomurmu.opencaller.core.testing.TestDispatcherProvider
import io.github.jeshiomurmu.opencaller.feature.callerid.announce.AnnouncePolicy
import io.github.jeshiomurmu.opencaller.feature.callerid.announce.AudioConditions
import io.github.jeshiomurmu.opencaller.feature.callerid.announce.CallerAnnouncer
import io.github.jeshiomurmu.opencaller.feature.callerid.announce.Speaker
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TC-UT-002: Announce Caller speaks the caller only when the mode, the entitlement and the phone's sound allow it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class AnnounceCallerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val supporter = StaticEntitlements(Entitlement(true, PaidTier.SUPPORTER, EntitlementSource.BILLING, Long.MAX_VALUE / 2))
    private val ringing = AudioConditions(headphones = false, ringerAudible = true, interruptionsAllowed = true)
    private val amina = CallerInfo(PhoneNumber("+8801712345678"), "Amina", NameSource.CONTACT)

    private class RecordingSpeaker : Speaker {
        val said = mutableListOf<String>()
        var stops = 0

        override fun speak(text: String) {
            said += text
        }

        override fun stop() {
            stops++
        }
    }

    @Test
    fun policyFollowsTheModeAndThePhonesSound() {
        val withHeadphones = ringing.copy(headphones = true)
        assertTrue(AnnouncePolicy.shouldAnnounce(AnnounceCallerMode.ALWAYS, entitled = true, ringing))
        assertFalse(AnnouncePolicy.shouldAnnounce(AnnounceCallerMode.HEADSET_ONLY, entitled = true, ringing))
        assertTrue(AnnouncePolicy.shouldAnnounce(AnnounceCallerMode.HEADSET_ONLY, entitled = true, withHeadphones))
        assertFalse(AnnouncePolicy.shouldAnnounce(AnnounceCallerMode.OFF, entitled = true, withHeadphones))
        // Not a supporter, a silenced ringer, Do Not Disturb: silent.
        assertFalse(AnnouncePolicy.shouldAnnounce(AnnounceCallerMode.ALWAYS, entitled = false, ringing))
        assertFalse(AnnouncePolicy.shouldAnnounce(AnnounceCallerMode.ALWAYS, entitled = true, ringing.copy(ringerAudible = false)))
        assertFalse(AnnouncePolicy.shouldAnnounce(AnnounceCallerMode.ALWAYS, entitled = true, ringing.copy(interruptionsAllowed = false)))
    }

    @Test
    fun speaksTheNameTheSpamWarningOrUnknown() {
        val speaker = RecordingSpeaker()
        val announcer = announcer(AnnounceCallerMode.ALWAYS, speaker)
        announcer.announce(amina)
        announcer.announce(CallerInfo(PhoneNumber("+12015550123"), spamVerdict = SpamVerdict(0.9f, RiskLevel.LIKELY_SPAM, "Loan", 214)))
        announcer.announce(CallerInfo(PhoneNumber("+12015550124")))
        announcer.announce(CallerInfo(null))
        assertEquals(
            listOf("Amina is calling", "Likely spam call", "Call from an unknown number", "Call from a hidden number"),
            speaker.said,
        )
        announcer.stop()
        assertEquals(1, speaker.stops)
    }

    @Test
    fun staysSilentWhenTheSettingsOrThePhoneSaySo() {
        val speaker = RecordingSpeaker()
        announcer(AnnounceCallerMode.OFF, speaker).announce(amina)
        announcer(AnnounceCallerMode.ALWAYS, speaker, entitlements = StaticEntitlements()).announce(amina)
        announcer(AnnounceCallerMode.ALWAYS, speaker, audio = ringing.copy(interruptionsAllowed = false)).announce(amina)
        announcer(AnnounceCallerMode.HEADSET_ONLY, speaker).announce(amina)
        assertEquals(emptyList<String>(), speaker.said)
        announcer(AnnounceCallerMode.HEADSET_ONLY, speaker, audio = ringing.copy(headphones = true)).announce(amina)
        assertEquals(listOf("Amina is calling"), speaker.said)
    }

    private fun announcer(
        mode: AnnounceCallerMode,
        speaker: Speaker,
        entitlements: StaticEntitlements = supporter,
        audio: AudioConditions = ringing,
    ) = CallerAnnouncer(
        context = context,
        settings = UserSettingsDataSource.inMemory(UserPreferences(announceCallerMode = mode)),
        entitlements = entitlements,
        audio = { audio },
        speaker = speaker,
        dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher()),
    )
}
