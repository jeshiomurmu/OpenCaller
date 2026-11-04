package io.github.jeshiomurmu.opencaller.feature.voip

import android.telecom.DisconnectCause
import io.github.jeshiomurmu.opencaller.core.model.AppCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/** How the call screen's state follows the server, the room and the user. */
class AppCallUiTest {
    private val ringingOut = AppCallUi(AppCallPhase.RINGING_OUT, incoming = false, number = "+12015550123", inviteId = "i1")
    private val ringingIn = AppCallUi(AppCallPhase.RINGING_IN, incoming = true, number = "+12015550123", inviteId = "i1")

    @Test
    fun theCallerLearnsWhyTheCallEnded() {
        assertEquals(AppCallEnd.DECLINED, ringingOut.afterServer(AppCallState.DECLINED).end)
        assertEquals(AppCallEnd.MISSED, ringingOut.afterServer(AppCallState.MISSED).end)
        assertSame(ringingOut, ringingOut.afterServer(AppCallState.ACCEPTED)) // active once the callee is in the room
        // The callee's own decline came from this phone: just ended.
        assertEquals(AppCallEnd.HUNG_UP, ringingIn.afterServer(AppCallState.DECLINED).end)
        assertEquals(AppCallEnd.HUNG_UP, ringingIn.afterServer(AppCallState.CANCELLED).end)
    }

    @Test
    fun theFirstEndWinsAndTheTimerStartsOnce() {
        val active = ringingOut.connected(1_000L)
        assertEquals(AppCallPhase.ACTIVE, active.phase)
        assertEquals(1_000L, active.connected(5_000L).connectedAtMillis)
        val ended = active.ended(AppCallEnd.HUNG_UP)
        assertFalse(ended.isLive)
        assertEquals(AppCallEnd.HUNG_UP, ended.ended(AppCallEnd.FAILED).end)
        assertEquals(AppCallPhase.ENDED, ended.connected(9_000L).phase)
    }

    @Test
    fun telecomHearsTheReason() {
        assertEquals(DisconnectCause.MISSED, disconnectCode(AppCallEnd.MISSED))
        assertEquals(DisconnectCause.LOCAL, disconnectCode(AppCallEnd.HUNG_UP))
        assertEquals(DisconnectCause.BUSY, disconnectCode(AppCallEnd.BUSY))
    }

    @Test
    fun everyPhaseHasAStatusLine() {
        AppCallPhase.entries.forEach { phase -> statusOf(AppCallUi(phase, incoming = true, number = null)) }
        assertEquals(R.string.voip_ended, statusOf(null))
    }
}
