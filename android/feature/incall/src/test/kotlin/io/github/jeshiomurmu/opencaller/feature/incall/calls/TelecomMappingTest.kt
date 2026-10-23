package io.github.jeshiomurmu.opencaller.feature.incall.calls

import android.os.Build
import android.os.Bundle
import android.telecom.Call
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallCapability
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Telecom's call constants to what the in-call screen shows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q, Build.VERSION_CODES.VANILLA_ICE_CREAM])
class TelecomMappingTest {
    @Test
    fun everyCallStateHasAStatus() {
        assertEquals(CallStatus.CONNECTING, statusOf(Call.STATE_NEW))
        assertEquals(CallStatus.CONNECTING, statusOf(Call.STATE_CONNECTING))
        assertEquals(CallStatus.CONNECTING, statusOf(Call.STATE_PULLING_CALL))
        assertEquals(CallStatus.DIALING, statusOf(Call.STATE_DIALING))
        assertEquals(CallStatus.RINGING, statusOf(Call.STATE_RINGING))
        assertEquals(CallStatus.RINGING, statusOf(Call.STATE_SIMULATED_RINGING))
        assertEquals(CallStatus.ACTIVE, statusOf(Call.STATE_ACTIVE))
        assertEquals(CallStatus.HOLDING, statusOf(Call.STATE_HOLDING))
        assertEquals(CallStatus.DISCONNECTING, statusOf(Call.STATE_DISCONNECTING))
        assertEquals(CallStatus.DISCONNECTED, statusOf(Call.STATE_DISCONNECTED))
        assertEquals(CallStatus.SELECT_SIM, statusOf(Call.STATE_SELECT_PHONE_ACCOUNT))
        assertEquals(CallStatus.OTHER, statusOf(Call.STATE_AUDIO_PROCESSING))
    }

    @Test
    fun directionAndCapabilities() {
        assertEquals(Direction.INCOMING, directionOf(Call.Details.DIRECTION_INCOMING))
        assertEquals(Direction.OUTGOING, directionOf(Call.Details.DIRECTION_OUTGOING))
        assertNull(directionOf(Call.Details.DIRECTION_UNKNOWN))

        val mask = Call.Details.CAPABILITY_HOLD or Call.Details.CAPABILITY_MUTE or Call.Details.CAPABILITY_SWAP_CONFERENCE
        assertEquals(setOf(CallCapability.HOLD, CallCapability.MUTE, CallCapability.SWAP_CONFERENCE), capabilitiesOf(mask))
        assertEquals(setOf(CallCapability.RESPOND_VIA_TEXT), capabilitiesOf(Call.Details.CAPABILITY_RESPOND_VIA_TEXT))
        assertTrue(capabilitiesOf(0).isEmpty())
    }

    @Test
    fun noSimSuggestionsWithoutExtras() {
        assertTrue(suggestedAccounts(null).isEmpty())
        assertTrue(suggestedAccounts(Bundle()).isEmpty())
    }
}
