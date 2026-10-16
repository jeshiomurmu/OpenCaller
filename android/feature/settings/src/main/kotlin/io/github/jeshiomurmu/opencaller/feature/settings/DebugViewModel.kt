package io.github.jeshiomurmu.opencaller.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.data.ScreeningEventRepository
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEvent
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.math.ceil

/**
 * Decision latency statistics (nearest-rank percentiles, in milliseconds).
 *
 * @property count number of decisions measured.
 */
data class LatencyStats(
    val count: Int,
    val p50: Int,
    val p95: Int,
    val max: Int,
) {
    /** How statistics are computed. */
    companion object {
        /**
         * Statistics over the *local* decisions in [events]: incoming calls decided on the device. Timeouts and errors
         * are excluded (they are the budget, not the engine's speed), and so are outgoing and emergency calls, which
         * skip screening. `null` when there is nothing to measure.
         */
        fun of(events: List<ScreeningEvent>): LatencyStats? {
            val latencies =
                events
                    .filter { it.direction == Direction.INCOMING && it.reason !in NOT_LOCAL }
                    .map { it.latencyMs }
                    .sorted()
            if (latencies.isEmpty()) return null
            return LatencyStats(latencies.size, latencies.percentile(MEDIAN), latencies.percentile(P95), latencies.last())
        }

        private const val MEDIAN = 0.50
        private const val P95 = 0.95
        private val NOT_LOCAL = setOf(ScreeningReason.TIMEOUT, ScreeningReason.ERROR, ScreeningReason.EMERGENCY, ScreeningReason.OUTGOING)

        private fun List<Int>.percentile(p: Double): Int = this[(ceil(p * size).toInt() - 1).coerceIn(0, lastIndex)]
    }
}

/** Debug screen. */
data class DebugUiState(
    val loading: Boolean = true,
    val events: List<ScreeningEvent> = emptyList(),
    val stats: LatencyStats? = null,
)

/** Settings → About → version ×7: the last 100 screening decisions and their latency (docs/architecture.md §12). */
@HiltViewModel
class DebugViewModel
    @Inject
    constructor(
        events: ScreeningEventRepository,
    ) : ViewModel() {
        /** Screen state. */
        val uiState: StateFlow<DebugUiState> =
            events
                .observeRecent(RECENT)
                .map { result ->
                    val recent = result.getOrNull().orEmpty()
                    DebugUiState(loading = false, events = recent, stats = LatencyStats.of(recent))
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DebugUiState())

        private companion object {
            const val RECENT = 100
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
