package io.github.jeshiomurmu.opencaller.feature.assistant

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantOffer
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.AssistantLine
import io.github.jeshiomurmu.opencaller.core.model.AssistantUsage
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreenedCall
import timber.log.Timber

/** Me → Settings → Call assistant, wired to its view model; codes open the dial pad. */
@Composable
fun AssistantRoute(
    modifier: Modifier = Modifier,
    viewModel: AssistantViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.onEvent(AssistantEvent.Refresh)
        onPauseOrDispose {}
    }
    AssistantScreen(state = state, onEvent = viewModel::onEvent, onDial = { dial(context, it) }, modifier = modifier)
}

// The dial pad with the code: the user presses Call, and the network answers on screen.
private fun dial(
    context: Context,
    code: String,
) {
    try {
        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", code, null)))
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "No dialer for a forwarding code")
    }
}

/** The setup with the forwarding codes, this month's minutes and the screened calls. */
@Composable
fun AssistantScreen(
    state: AssistantUiState,
    onEvent: (AssistantEvent) -> Unit,
    onDial: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmOff by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        item { Intro(state) }
        if (state.offer == AssistantOffer.AVAILABLE) item { Setup(state, onEvent, onDial, onTurnOff = { confirmOff = true }) }
        state.usage?.let { usage -> item { Usage(usage) } }
        item { SectionTitle(R.string.assistant_history_title) }
        if (state.history.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.assistant_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        items(
            state.history,
            key = ScreenedCall::id,
        ) { call -> ScreenedCallCard(call, onDelete = { onEvent(AssistantEvent.Delete(call.id)) }) }
    }
    if (confirmOff) {
        TurnOffDialog(onConfirm = { onEvent(AssistantEvent.TurnOff) }, onDismiss = { confirmOff = false })
    }
}

// Get a number, or the codes for the number and Turn off.
@Composable
private fun Setup(
    state: AssistantUiState,
    onEvent: (AssistantEvent) -> Unit,
    onDial: (String) -> Unit,
    onTurnOff: () -> Unit,
) {
    val line = state.line
    if (line == null) {
        Button(onClick = { onEvent(AssistantEvent.TurnOn) }, enabled = !state.busy) { Text(stringResource(R.string.assistant_turn_on)) }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
            Forwarding(line, state.codes, onDial)
            OutlinedButton(onClick = onTurnOff) { Text(stringResource(R.string.assistant_turn_off)) }
        }
    }
}

@Composable
private fun TurnOffDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.assistant_turn_off_title)) },
        text = { Text(stringResource(R.string.assistant_turn_off_text, ForwardingCodes.CANCEL_ALL)) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(stringResource(R.string.assistant_turn_off_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.assistant_cancel)) } },
    )
}

@Composable
private fun Intro(state: AssistantUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s), modifier = Modifier.padding(top = OpenCallerSpacing.m)) {
        Text(
            stringResource(R.string.assistant_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.assistant_intro), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.assistant_privacy), style = MaterialTheme.typography.bodySmall)
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        offerText(state.offer)?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        state.message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
    }
}

@OpenCallerPreviews
@Composable
private fun AssistantScreenPreview() {
    OpenCallerTheme {
        val line = AssistantLine(PhoneNumber("+8809638000001"), "BD")
        AssistantScreen(
            state =
                AssistantUiState(
                    offer = AssistantOffer.AVAILABLE,
                    line = line,
                    codes = ForwardingCodes.forNumber(line.number),
                    usage = AssistantUsage(month = "2026-11", usedSeconds = 754, limitMinutes = 100, maxCallMinutes = 5),
                ),
            onEvent = {},
            onDial = {},
        )
    }
}
