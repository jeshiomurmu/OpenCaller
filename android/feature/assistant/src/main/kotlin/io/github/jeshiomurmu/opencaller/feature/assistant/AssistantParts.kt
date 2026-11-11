package io.github.jeshiomurmu.opencaller.feature.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantOffer
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.model.AssistantLine
import io.github.jeshiomurmu.opencaller.core.model.AssistantUsage

// Parts of the setup screen: the forwarding codes card and the minutes meter.

internal fun offerText(offer: AssistantOffer?): Int? =
    when (offer) {
        AssistantOffer.SIGNED_OUT -> R.string.assistant_signed_out
        AssistantOffer.NOT_ON_SERVER -> R.string.assistant_not_on_server
        AssistantOffer.NOT_IN_BUILD -> R.string.assistant_not_in_build
        AssistantOffer.AVAILABLE, null -> null
    }

@Composable
internal fun Forwarding(
    line: AssistantLine,
    codes: List<ForwardingCode>,
    onDial: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(OpenCallerSpacing.s), verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
            Text(stringResource(R.string.assistant_your_number, line.number.e164), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.assistant_forwarding_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.assistant_forwarding_intro), style = MaterialTheme.typography.bodySmall)
            codes.forEach { code -> CodeRow(stringResource(labelOf(code.kind)), code.code, onDial) }
            Text(stringResource(R.string.assistant_cancel_title), style = MaterialTheme.typography.titleSmall)
            CodeRow(stringResource(R.string.assistant_cancel_text, ForwardingCodes.CANCEL_ALL), ForwardingCodes.CANCEL_ALL, onDial)
            Text(stringResource(R.string.assistant_carrier_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.assistant_carrier_text), style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun labelOf(kind: ForwardingKind): Int =
    when (kind) {
        ForwardingKind.NO_REPLY -> R.string.assistant_code_no_reply
        ForwardingKind.UNREACHABLE -> R.string.assistant_code_unreachable
        ForwardingKind.BUSY -> R.string.assistant_code_busy
    }

@Composable
internal fun CodeRow(
    label: String,
    code: String,
    onDial: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(code, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { onDial(code) }) { Text(stringResource(R.string.assistant_dial)) }
    }
}

@Composable
internal fun Usage(usage: AssistantUsage) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
        SectionTitle(R.string.assistant_usage_title)
        if (usage.limitMinutes > 0) {
            Text(
                pluralStringResource(R.plurals.assistant_usage, usage.limitMinutes, usage.usedMinutes, usage.limitMinutes),
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { (usage.usedMinutes.toFloat() / usage.limitMinutes).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                pluralStringResource(R.plurals.assistant_usage_unlimited, usage.usedMinutes, usage.usedMinutes),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (usage.exhausted) Text(stringResource(R.string.assistant_usage_exhausted), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
internal fun SectionTitle(title: Int) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}
