package io.github.jeshiomurmu.opencaller.feature.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantMessages
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.CallUrgency
import io.github.jeshiomurmu.opencaller.core.model.CallerDetails
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.Speaker
import io.github.jeshiomurmu.opencaller.core.model.TranscriptLine

/** The live transcript with Call back, Quick reply and Spam (F-UT-04). */
@Composable
fun LiveScreeningScreen(
    state: LiveUiState,
    onEvent: (LiveEvent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(OpenCallerSpacing.screenPadding),
            verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
        ) {
            CallHeader(state)
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xxs)) {
                items(state.lines) { line -> Transcript(listOf(line)) }
                state.partial?.let {
                    item {
                        Text(
                            "${stringResource(R.string.assistant_live_caller)}: $it…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (state.phase ==
                LivePhase.LIVE
            ) {
                Actions(state, onEvent)
            } else {
                TextButton(onClick = onClose) { Text(stringResource(R.string.assistant_live_close)) }
            }
        }
    }
}

@Composable
private fun CallHeader(state: LiveUiState) {
    Text(
        stringResource(R.string.assistant_live_title),
        style = MaterialTheme.typography.titleLarge,
        modifier =
            Modifier.semantics {
                heading()
            },
    )
    Text(
        state.caller?.e164 ?: if (state.callerHidden) stringResource(R.string.assistant_hidden_number) else "",
        style = MaterialTheme.typography.headlineSmall,
    )
    Status(state)
    state.details?.let { DetailsText(it) }
    state.message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun Status(state: LiveUiState) {
    when (state.phase) {
        LivePhase.CONNECTING -> {
            Text(stringResource(R.string.assistant_live_connecting))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        LivePhase.FAILED -> {
            Text(stringResource(R.string.assistant_live_failed), color = MaterialTheme.colorScheme.error)
        }

        LivePhase.NOT_IN_BUILD -> {
            Text(stringResource(R.string.assistant_live_not_in_build), color = MaterialTheme.colorScheme.error)
        }

        LivePhase.ENDED -> {
            Text(stringResource(endText(state.end) ?: R.string.assistant_live_ended))
        }

        LivePhase.LIVE -> {
            Unit
        }
    }
}

@Composable
private fun Actions(
    state: LiveUiState,
    onEvent: (LiveEvent) -> Unit,
) {
    var reply by rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
        OutlinedTextField(
            value = reply,
            onValueChange = { reply = it.take(AssistantMessages.MAX_REPLY_CHARS) },
            label = { Text(stringResource(R.string.assistant_live_reply_hint)) },
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                onEvent(LiveEvent.Reply(reply))
                reply = ""
            },
            enabled = reply.isNotBlank(),
        ) { Text(stringResource(R.string.assistant_live_send)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
        Button(onClick = {
            onEvent(LiveEvent.CallBack)
        }, enabled = state.caller != null) { Text(stringResource(R.string.assistant_live_call_back)) }
        OutlinedButton(onClick = { onEvent(LiveEvent.Spam) }) { Text(stringResource(R.string.assistant_live_spam)) }
    }
}

@OpenCallerPreviews
@Composable
private fun LiveScreeningScreenPreview() {
    OpenCallerTheme {
        LiveScreeningScreen(
            state =
                LiveUiState(
                    phase = LivePhase.LIVE,
                    caller = PhoneNumber("+8801711000099"),
                    lines =
                        listOf(
                            TranscriptLine(Speaker.ASSISTANT, "Hello, you've reached an automated call assistant."),
                            TranscriptLine(Speaker.CALLER, "Hi, this is Rahim from the clinic."),
                        ),
                    partial = "It's about tomorrow",
                    details = CallerDetails("Rahim", "Tomorrow's appointment", CallUrgency.HIGH),
                ),
            onEvent = {},
            onClose = {},
        )
    }
}
