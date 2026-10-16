package io.github.jeshiomurmu.opencaller.feature.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.CallerVerification
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEvent
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason

/** The debug screen, wired to its view model. */
@Composable
fun DebugRoute(
    modifier: Modifier = Modifier,
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DebugScreen(state = state, modifier = modifier)
}

/**
 * Screening latency (p50/p95 of local decisions) and the last 100 decisions. Numbers are shown with only their last
 * four digits, like the logs (docs/architecture.md §10).
 */
@Composable
fun DebugScreen(
    state: DebugUiState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = OpenCallerSpacing.screenPadding)) {
        item {
            Text(
                text = stringResource(R.string.debug_title),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(vertical = OpenCallerSpacing.m).semantics { heading() },
            )
            val stats = state.stats
            Text(
                text =
                    if (stats == null) {
                        stringResource(R.string.debug_no_decisions)
                    } else {
                        stringResource(R.string.debug_latency, stats.p50, stats.p95, stats.max, stats.count)
                    },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = pluralStringResource(R.plurals.debug_recent, state.events.size, state.events.size),
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = OpenCallerSpacing.xs))
        }
        items(state.events, key = ScreeningEvent::id) { EventRow(it) }
    }
}

@Composable
private fun EventRow(event: ScreeningEvent) {
    val time = DateUtils.formatDateTime(LocalContext.current, event.atMillis, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE)
    val number = event.number?.let { "…" + it.e164.takeLast(VISIBLE_DIGITS) } ?: stringResource(R.string.debug_hidden)
    val details =
        listOfNotNull(
            time,
            event.direction.name.lowercase(),
            number,
            event.spamScore?.let { stringResource(R.string.debug_spam, it.toString()) },
            event.verification.takeIf { it != CallerVerification.UNKNOWN }?.let {
                stringResource(
                    R.string.debug_stir,
                    it.name.lowercase(),
                )
            },
        )
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = OpenCallerSpacing.xxs)) {
        Text(
            text = stringResource(R.string.debug_event, event.decision.name, event.reason.code, event.latencyMs),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = details.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private const val VISIBLE_DIGITS = 4

private const val PREVIEW_FAST_MS = 4
private const val PREVIEW_TIMEOUT_MS = 1_500

@OpenCallerPreviews
@Composable
private fun DebugScreenPreview() {
    val events =
        listOf(
            ScreeningEvent(
                1,
                PhoneNumber("+15551230001"),
                Direction.INCOMING,
                Decision.BLOCK,
                ScreeningReason.RULE,
                null,
                CallerVerification.PASSED,
                PREVIEW_FAST_MS,
                0,
            ),
            ScreeningEvent(
                2,
                null,
                Direction.INCOMING,
                Decision.ALLOW,
                ScreeningReason.TIMEOUT,
                null,
                CallerVerification.UNKNOWN,
                PREVIEW_TIMEOUT_MS,
                0,
            ),
        )
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            DebugScreen(state = DebugUiState(loading = false, events = events, stats = LatencyStats.of(events)))
        }
    }
}
