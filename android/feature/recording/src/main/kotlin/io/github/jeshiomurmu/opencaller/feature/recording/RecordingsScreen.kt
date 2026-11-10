package io.github.jeshiomurmu.opencaller.feature.recording

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.SavedRecording

/** Me → Settings → Recordings, wired to its view model. */
@Composable
fun RecordingsRoute(
    modifier: Modifier = Modifier,
    viewModel: RecordingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.onEvent(RecordingsEvent.Refresh)
        onPauseOrDispose {}
    }
    RecordingsScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

/** Saved recordings (only on this phone), playback, transcripts and delete. */
@Composable
fun RecordingsScreen(
    state: RecordingsUiState,
    onEvent: (RecordingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        item { Header(state, onEvent) }
        if (state.recordings.isEmpty()) {
            item { Text(stringResource(R.string.recordings_empty), style = MaterialTheme.typography.bodyMedium) }
        }
        items(state.recordings, key = SavedRecording::id) { recording ->
            RecordingCard(
                recording = recording,
                playing = state.playingId == recording.id,
                onPlay = { onEvent(RecordingsEvent.TogglePlay(recording)) },
                onDelete = { deleting = recording.id },
            )
        }
    }
    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.recordings_delete_title)) },
            text = { Text(stringResource(R.string.recordings_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    onEvent(RecordingsEvent.Delete(id))
                    deleting = null
                }) { Text(stringResource(R.string.recordings_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.recordings_cancel)) } },
        )
    }
}

@Composable
private fun Header(
    state: RecordingsUiState,
    onEvent: (RecordingsEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s), modifier = Modifier.padding(top = OpenCallerSpacing.m)) {
        Text(
            stringResource(R.string.recordings_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.recordings_intro), style = MaterialTheme.typography.bodyMedium)
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        state.transcripts?.let { on ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.recordings_transcripts), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.recordings_transcripts_summary), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = on, enabled = !state.busy, onCheckedChange = { onEvent(RecordingsEvent.SetTranscripts(it)) })
            }
        }
    }
}

@Composable
private fun RecordingCard(
    recording: SavedRecording,
    playing: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(OpenCallerSpacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        recording.otherParty?.e164 ?: stringResource(R.string.recordings_unknown_party),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        DateUtils.getRelativeTimeSpanString(recording.createdAtMillis).toString() + " · " +
                            DateUtils.formatElapsedTime(recording.durationMs / MILLIS_PER_SECOND),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onPlay) {
                    Icon(
                        painterResource(if (playing) OpenCallerIcons.Hold else OpenCallerIcons.Play),
                        contentDescription = stringResource(if (playing) R.string.recordings_stop else R.string.recordings_play),
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(painterResource(OpenCallerIcons.Delete), contentDescription = stringResource(R.string.recordings_delete))
                }
            }
            recording.transcript?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private const val MILLIS_PER_SECOND = 1_000L

@OpenCallerPreviews
@Composable
private fun RecordingsScreenPreview() {
    OpenCallerTheme {
        RecordingsScreen(
            state =
                RecordingsUiState(
                    recordings =
                        listOf(
                            SavedRecording(
                                id = "1",
                                otherParty = PhoneNumber("+8801711000001"),
                                filePath = "",
                                durationMs = 61_000,
                                transcript = "Hello, this is a test.",
                                createdAtMillis = 0,
                            ),
                        ),
                    transcripts = false,
                ),
            onEvent = {},
        )
    }
}
