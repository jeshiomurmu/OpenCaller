package io.github.jeshiomurmu.opencaller.feature.callerid

import android.media.AudioManager
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallEndDetectorTest {
    @Test
    fun endsOnIdleAfterRingingOrActiveOnlyOnce() {
        val detector = CallEndDetector()
        // Screening happens before the phone rings, so an IDLE first report is not an end.
        assertFalse(detector.onPhase(CallPhase.IDLE))
        assertFalse(detector.onPhase(CallPhase.RINGING))
        assertFalse(detector.onPhase(CallPhase.ACTIVE))
        assertTrue(detector.onPhase(CallPhase.IDLE))
        assertFalse(detector.onPhase(CallPhase.IDLE))
        assertFalse(detector.onPhase(CallPhase.RINGING))
    }

    @Test
    fun statesAndAudioModesMapToPhases() {
        assertEquals(CallPhase.IDLE, TelephonyManager.CALL_STATE_IDLE.toPhase())
        assertEquals(CallPhase.RINGING, TelephonyManager.CALL_STATE_RINGING.toPhase())
        assertEquals(CallPhase.ACTIVE, TelephonyManager.CALL_STATE_OFFHOOK.toPhase())
        assertEquals(CallPhase.IDLE, AudioManager.MODE_NORMAL.audioModeToPhase())
        assertEquals(CallPhase.RINGING, AudioManager.MODE_RINGTONE.audioModeToPhase())
        assertEquals(CallPhase.ACTIVE, AudioManager.MODE_IN_CALL.audioModeToPhase())
        assertEquals(CallPhase.ACTIVE, AudioManager.MODE_IN_COMMUNICATION.audioModeToPhase())
        assertEquals(CallPhase.IDLE, AudioManager.MODE_INVALID.audioModeToPhase())
    }
}
