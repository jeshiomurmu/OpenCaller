package io.github.jeshiomurmu.opencaller.feature.settings

import io.github.jeshiomurmu.opencaller.core.model.CallerVerification
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEvent
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyStatsTest {
    private fun event(
        latency: Int,
        reason: ScreeningReason = ScreeningReason.NO_MATCH,
        direction: Direction = Direction.INCOMING,
    ) = ScreeningEvent(0, null, direction, Decision.ALLOW, reason, null, CallerVerification.UNKNOWN, latency, 0)

    @Test
    fun nearestRankPercentilesOverLocalDecisions() {
        val events = (1..100).map { event(it) } + event(1500, ScreeningReason.TIMEOUT) + event(900, direction = Direction.OUTGOING)
        val stats = LatencyStats.of(events)
        assertEquals(LatencyStats(count = 100, p50 = 50, p95 = 95, max = 100), stats)
    }

    @Test
    fun smallSamples() {
        assertNull(LatencyStats.of(emptyList()))
        assertNull(LatencyStats.of(listOf(event(3, ScreeningReason.ERROR), event(1, ScreeningReason.EMERGENCY))))
        assertEquals(LatencyStats(1, 7, 7, 7), LatencyStats.of(listOf(event(7))))
        assertEquals(LatencyStats(3, 4, 30, 30), LatencyStats.of(listOf(event(30), event(2), event(4))))
    }
}
