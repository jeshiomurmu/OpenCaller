package io.github.jeshiomurmu.opencaller.core.data.recording

import io.github.jeshiomurmu.opencaller.core.model.RecordingSessionState
import io.github.jeshiomurmu.opencaller.core.model.ScamCue
import io.github.jeshiomurmu.opencaller.core.model.VoiceScanResult
import io.github.jeshiomurmu.opencaller.core.model.VoiceScanSession
import io.github.jeshiomurmu.opencaller.core.network.ScanSessionDto
import org.junit.Assert.assertEquals
import org.junit.Test

/** The server's scan session as the phone reads it (ADR 0011). */
class VoiceScanMappingTest {
    private val id = "0f8fad5b-d9cb-469f-a165-70867728950e"

    @Test
    fun anEndedScanCarriesItsResultAndKnownCues() {
        assertEquals(
            VoiceScanSession(RecordingSessionState.ENDED, VoiceScanResult(0.83f, listOf(ScamCue.OTP_REQUEST, ScamCue.URGENCY))),
            ScanSessionDto(id, "ended", 0.83f, listOf("otp_request", "urgency", "a_newer_cue", "urgency")).toModel(),
        )
    }

    @Test
    fun otherStatesCarryNoResultAndABadProbabilityReadsAsFailed() {
        assertEquals(VoiceScanSession(RecordingSessionState.ACTIVE), ScanSessionDto(id, "active").toModel())
        assertEquals(VoiceScanSession(RecordingSessionState.FAILED), ScanSessionDto(id, "ended").toModel())
        assertEquals(VoiceScanSession(RecordingSessionState.FAILED), ScanSessionDto(id, "ended", 1.5f).toModel())
        assertEquals(VoiceScanSession(RecordingSessionState.FAILED), ScanSessionDto(id, "dancing").toModel())
    }
}
