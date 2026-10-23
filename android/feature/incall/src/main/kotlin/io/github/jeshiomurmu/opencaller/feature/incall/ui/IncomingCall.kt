package io.github.jeshiomurmu.opencaller.feature.incall.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallCapability
import io.github.jeshiomurmu.opencaller.feature.incall.CallView
import io.github.jeshiomurmu.opencaller.feature.incall.InCallEvent
import io.github.jeshiomurmu.opencaller.feature.incall.R

/**
 * A ringing call with nothing else in progress: the caller card, then Decline, Message (when the network lets the
 * call be declined with a text) and Answer. Nothing else appears here (docs/design.md §7, "calls are sacred").
 */
@Composable
internal fun IncomingCall(
    view: CallView,
    nowMillis: Long,
    onEvent: (InCallEvent) -> Unit,
    onReply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        CallHeader(view = view, nowMillis = nowMillis, modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = OpenCallerSpacing.l),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
        ) {
            RoundAction(
                icon = OpenCallerIcons.CallEnd,
                label = stringResource(R.string.incall_decline),
                container = OpenCallerTheme.semanticColors.danger,
                content = OpenCallerTheme.semanticColors.onDanger,
                onClick = { onEvent(InCallEvent.Decline(view.call.id)) },
            )
            if (view.call.can(CallCapability.RESPOND_VIA_TEXT)) {
                RoundAction(
                    icon = OpenCallerIcons.Message,
                    label = stringResource(R.string.incall_message),
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onReply,
                )
            }
            RoundAction(
                icon = OpenCallerIcons.Calls,
                label = stringResource(R.string.incall_answer),
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                onClick = { onEvent(InCallEvent.Answer(view.call.id)) },
            )
        }
    }
}

/** A second call ringing while one is in progress (call waiting): who it is, Decline and Answer. */
@Composable
internal fun WaitingCall(
    view: CallView,
    onEvent: (InCallEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = OpenCallerShape.card, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(modifier = Modifier.padding(OpenCallerSpacing.m), verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
            Text(text = stringResource(R.string.incall_incoming), style = MaterialTheme.typography.labelLarge)
            Text(text = titleOf(view), style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
                OutlinedButton(onClick = { onEvent(InCallEvent.Decline(view.call.id)) }) {
                    Text(stringResource(R.string.incall_decline))
                }
                Button(onClick = { onEvent(InCallEvent.Answer(view.call.id)) }) {
                    Text(stringResource(R.string.incall_answer))
                }
            }
        }
    }
}

@OpenCallerPreviews
@Composable
private fun IncomingCallPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            IncomingCall(view = InCallSamples.scamCaller, nowMillis = InCallSamples.NOW, onEvent = {}, onReply = {})
        }
    }
}

@OpenCallerPreviews
@Composable
private fun WaitingCallPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            WaitingCall(view = InCallSamples.contact, onEvent = {})
        }
    }
}
