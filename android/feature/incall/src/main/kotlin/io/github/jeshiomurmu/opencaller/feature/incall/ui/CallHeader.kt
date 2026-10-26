package io.github.jeshiomurmu.opencaller.feature.incall.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import io.github.jeshiomurmu.opencaller.core.ui.CallerCard
import io.github.jeshiomurmu.opencaller.feature.incall.CallView
import io.github.jeshiomurmu.opencaller.feature.incall.R
import io.github.jeshiomurmu.opencaller.feature.incall.statusText
import io.github.jeshiomurmu.opencaller.core.ui.R as UiR

/**
 * Who is on the call, with the caller card (docs/design.md §5: name with its source, risk banner, details), then the
 * call's state or its duration. A number that isn't a phone number (an emergency number, a short code) is shown as
 * it is; until the lookup finishes, the number alone.
 */
@Composable
internal fun CallHeader(
    view: CallView,
    nowMillis: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
    ) {
        val caller = view.caller
        if (caller != null && (caller.number != null || view.call.hidden)) {
            CallerCard(caller = caller, formattedNumber = view.number)
        } else {
            Text(
                text = view.number ?: stringResource(UiR.string.caller_card_hidden),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(OpenCallerSpacing.m).semantics { heading() },
            )
        }
        Text(
            text = statusLine(view, nowMillis),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** "Calling…", "On hold", "Conference call · 3 people" or the duration of a connected call. */
@Composable
internal fun statusLine(
    view: CallView,
    nowMillis: Long,
): String {
    val call = view.call
    val state =
        if (call.status == CallStatus.ACTIVE && call.connectedAtMillis > 0) {
            DateUtils.formatElapsedTime(((nowMillis - call.connectedAtMillis) / MILLIS_PER_SECOND).coerceAtLeast(0))
        } else {
            stringResource(statusText(call.status))
        }
    return if (call.isConference) {
        listOf(
            stringResource(R.string.incall_conference),
            pluralStringResource(R.plurals.incall_conference_count, call.childIds.size, call.childIds.size),
            state,
        ).joinToString(stringResource(UiR.string.risk_banner_separator))
    } else {
        state
    }
}

private const val MILLIS_PER_SECOND = 1_000L

@OpenCallerPreviews
@Composable
private fun CallHeaderPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            CallHeader(view = InCallSamples.scamCaller, nowMillis = InCallSamples.NOW)
        }
    }
}
