package io.github.jeshiomurmu.opencaller.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TC-SCAN-001's wording rule: "Possibly AI-generated" from 0.5 up, "Likely human" below. */
class VoiceScanTest {
    @Test
    fun theVerdictFollowsTheThreshold() {
        assertEquals(VoiceVerdict.LIKELY_HUMAN, VoiceScanResult(0f, emptyList()).verdict)
        assertEquals(VoiceVerdict.LIKELY_HUMAN, VoiceScanResult(0.49f, emptyList()).verdict)
        assertEquals(VoiceVerdict.POSSIBLY_AI, VoiceScanResult(0.5f, emptyList()).verdict)
        assertEquals(VoiceVerdict.POSSIBLY_AI, VoiceScanResult(1f, emptyList()).verdict)
    }

    @Test
    fun cuesAreReadByTheirWireIds() {
        assertEquals(ScamCue.OTP_REQUEST, ScamCue.of("otp_request"))
        assertEquals(ScamCue.PERSONAL_DATA, ScamCue.of("personal_data"))
        assertNull(ScamCue.of("lottery"))
        // The same ten ids as the server's ScanCues and the agent's CUE_IDS.
        assertEquals(10, ScamCue.entries.size)
    }
}
