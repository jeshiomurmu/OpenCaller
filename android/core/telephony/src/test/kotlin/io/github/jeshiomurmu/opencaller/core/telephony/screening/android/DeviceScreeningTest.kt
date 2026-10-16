package io.github.jeshiomurmu.opencaller.core.telephony.screening.android

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.Connection
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.model.CallerVerification
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningTestHooks
import io.github.jeshiomurmu.opencaller.core.telephony.screening.device.AndroidContactLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.device.DeviceHomeRegionProvider
import io.github.jeshiomurmu.opencaller.core.telephony.screening.device.LocalVerifiedBusinessLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.device.callResponseFor
import io.github.jeshiomurmu.opencaller.core.telephony.screening.device.screeningCall
import io.github.jeshiomurmu.opencaller.core.testing.TestDispatcherProvider
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.Optional

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q, Build.VERSION_CODES.VANILLA_ICE_CREAM])
class DeviceScreeningTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val number = PhoneNumber("+12015550123")

    @Test
    fun callResponsesMatchTheDecision() {
        val block = callResponseFor(Decision.BLOCK)
        assertTrue(block.disallowCall)
        assertTrue(block.rejectCall)
        assertTrue(block.skipNotification)
        assertFalse(block.skipCallLog)
        assertFalse(block.silenceCall)

        val silence = callResponseFor(Decision.SILENCE)
        assertTrue(silence.silenceCall)
        assertFalse(silence.disallowCall)

        val allow = callResponseFor(Decision.ALLOW)
        assertFalse(allow.disallowCall || allow.rejectCall || allow.silenceCall || allow.skipNotification)
    }

    @Test
    fun callDetailsMapping() {
        val incoming = screeningCall("+12015550123", Call.Details.DIRECTION_INCOMING, Connection.VERIFICATION_STATUS_PASSED)
        assertEquals("+12015550123", incoming.handle)
        assertEquals(Direction.INCOMING, incoming.direction)
        assertEquals(CallerVerification.PASSED, incoming.verification)

        val outgoing = screeningCall("+12015550123", Call.Details.DIRECTION_OUTGOING, null)
        assertEquals(Direction.OUTGOING, outgoing.direction)
        assertEquals(CallerVerification.UNKNOWN, outgoing.verification)

        // Unknown direction is treated as incoming (it can still be blocked, never an outgoing call).
        assertEquals(Direction.INCOMING, screeningCall(null, Call.Details.DIRECTION_UNKNOWN, null).direction)
        assertEquals(CallerVerification.FAILED, screeningCall(null, 0, Connection.VERIFICATION_STATUS_FAILED).verification)
        assertEquals(CallerVerification.NOT_VERIFIED, screeningCall(null, 0, Connection.VERIFICATION_STATUS_NOT_VERIFIED).verification)
    }

    @Test
    fun contactsAreNotReadWithoutPermission() =
        runTest {
            val lookup = AndroidContactLookup(context, TestDispatcherProvider(UnconfinedTestDispatcher(testScheduler)))
            assertFalse(lookup.canRead())
            assertNull(lookup.displayName(number))

            shadowOf(context as Application).grantPermissions(Manifest.permission.READ_CONTACTS)
            assertTrue(lookup.canRead())
            // Robolectric has no contacts provider: the lookup finds nothing instead of failing.
            assertNull(lookup.displayName(number))
        }

    @Test
    fun homeRegionPrefersQaOverrideThenSimThenLocale() {
        val telephony = shadowOf(context.getSystemService(TelephonyManager::class.java))
        telephony.setSimCountryIso("")
        telephony.setNetworkCountryIso("")
        assertEquals(Locale.getDefault().country.uppercase(), DeviceHomeRegionProvider(context, Optional.empty()).homeRegion())

        telephony.setSimCountryIso("bd")
        assertEquals("BD", DeviceHomeRegionProvider(context, Optional.empty()).homeRegion())

        assertEquals("NG", DeviceHomeRegionProvider(context, Optional.of(hooks(region = "ng"))).homeRegion())
    }

    @Test
    fun verifiedBusinessesComeOnlyFromQaHooksInPhase3() =
        runTest {
            assertFalse(LocalVerifiedBusinessLookup(Optional.empty()).isVerifiedBusiness(number))
            assertTrue(LocalVerifiedBusinessLookup(Optional.of(hooks(verified = number))).isVerifiedBusiness(number))
        }

    private fun hooks(
        region: String? = null,
        verified: PhoneNumber? = null,
    ) = object : ScreeningTestHooks {
        override fun homeRegionOverride(): String? = region

        override fun isVerifiedBusiness(number: PhoneNumber): Boolean = number == verified
    }
}
