package io.github.jeshiomurmu.opencaller.feature.settings.ghostcall

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.GhostCallSetup
import io.github.jeshiomurmu.opencaller.core.ui.SupporterFeatureCard
import io.github.jeshiomurmu.opencaller.feature.settings.R

/** Settings → Ghost Call, wired to its view model. */
@Composable
fun GhostCallRoute(
    onOpenSupporter: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GhostCallViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let {
                viewModel.onEvent(GhostCallEvent.PhotoPicked(it.toString()))
            }
        }
    GhostCallScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onPickPhoto = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onOpenSupporter = onOpenSupporter,
        modifier = modifier,
    )
}

/** Name, number, photo and delay of the fake call, then Schedule (supporters). */
@Composable
fun GhostCallScreen(
    state: GhostCallUiState,
    onEvent: (GhostCallEvent) -> Unit,
    onPickPhoto: () -> Unit,
    onOpenSupporter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenCallerSpacing.screenPadding),
    ) {
        Text(
            stringResource(R.string.ghost_call_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier =
                Modifier.semantics {
                    heading()
                },
        )
        Text(stringResource(R.string.ghost_call_explainer), style = MaterialTheme.typography.bodyMedium)
        if (!state.entitled) SupporterFeatureCard(onOpenSupporter = onOpenSupporter)
        OutlinedTextField(
            value = state.setup.callerName,
            onValueChange = { onEvent(GhostCallEvent.Name(it)) },
            label = { Text(stringResource(R.string.ghost_call_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.setup.displayNumber,
            onValueChange = { onEvent(GhostCallEvent.Number(it)) },
            label = { Text(stringResource(R.string.ghost_call_number)) },
            supportingText = { Text(stringResource(R.string.ghost_call_number_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
        PhotoRow(state, onEvent, onPickPhoto)
        DelayChips(state.setup.delaySeconds, onEvent)
        ScheduleRow(state, onEvent)
    }
}

@Composable
private fun PhotoRow(
    state: GhostCallUiState,
    onEvent: (GhostCallEvent) -> Unit,
    onPickPhoto: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
        OutlinedButton(onClick = onPickPhoto) {
            Text(stringResource(if (state.setup.hasPhoto) R.string.ghost_call_change_photo else R.string.ghost_call_add_photo))
        }
        if (state.setup.hasPhoto) {
            TextButton(onClick = {
                onEvent(GhostCallEvent.RemovePhoto)
            }) { Text(stringResource(R.string.ghost_call_remove_photo)) }
        }
    }
    if (state.photoFailed) {
        Text(
            stringResource(R.string.ghost_call_photo_failed),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DelayChips(
    selected: Int,
    onEvent: (GhostCallEvent) -> Unit,
) {
    Text(stringResource(R.string.ghost_call_delay), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        GhostCallSetup.DELAYS.forEach { seconds ->
            FilterChip(
                selected = seconds == selected,
                onClick = { onEvent(GhostCallEvent.Delay(seconds)) },
                label = { Text(delayText(seconds)) },
            )
        }
    }
}

@Composable
private fun ScheduleRow(
    state: GhostCallUiState,
    onEvent: (GhostCallEvent) -> Unit,
) {
    val waiting = state.scheduledSeconds
    if (waiting != null) {
        Text(stringResource(R.string.ghost_call_scheduled, delayText(waiting)), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = { onEvent(GhostCallEvent.Cancel) }) { Text(stringResource(R.string.ghost_call_cancel)) }
    } else {
        Button(
            onClick = { onEvent(GhostCallEvent.Schedule) },
            enabled = state.entitled,
        ) { Text(stringResource(R.string.ghost_call_schedule)) }
    }
}

@Composable
private fun delayText(seconds: Int): String =
    if (seconds < SECONDS_PER_MINUTE) {
        pluralStringResource(R.plurals.ghost_call_seconds, seconds, seconds)
    } else {
        pluralStringResource(R.plurals.ghost_call_minutes, seconds / SECONDS_PER_MINUTE, seconds / SECONDS_PER_MINUTE)
    }

private const val SECONDS_PER_MINUTE = 60

@OpenCallerPreviews
@Composable
private fun GhostCallScreenPreview() {
    OpenCallerTheme {
        GhostCallScreen(
            state = GhostCallUiState(entitled = true, setup = GhostCallSetup("Mum", "+44 7700 900123")),
            onEvent = {},
            onPickPhoto = {},
            onOpenSupporter = {},
        )
    }
}
