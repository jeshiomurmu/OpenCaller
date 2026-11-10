package io.github.jeshiomurmu.opencaller.feature.incall.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallRecordingStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.RecordingFailure
import io.github.jeshiomurmu.opencaller.core.ui.CallControlButton
import io.github.jeshiomurmu.opencaller.feature.incall.InCallEvent
import io.github.jeshiomurmu.opencaller.feature.incall.InCallUiState
import io.github.jeshiomurmu.opencaller.feature.incall.R

/**
 * Record (or Stop while recording). Record first shows the consent reminder (docs/prd.md F-UT-03, ADR 0009): both
 * parties hear a beep and an announcement, and the user is responsible for the law where the other party is.
 */
@Composable
internal fun RecordControl(
    state: InCallUiState,
    onEvent: (InCallEvent) -> Unit,
) {
    var reminder by rememberSaveable { mutableStateOf(false) }
    val recording = state.recording is CallRecordingStatus.Recording
    CallControlButton(
        icon = OpenCallerIcons.Record,
        label = stringResource(if (recording) R.string.incall_record_stop else R.string.incall_record),
        checked = recording,
        enabled = recording || state.canRecord,
        onClick = { if (recording) onEvent(InCallEvent.StopRecording) else reminder = true },
    )
    if (reminder) {
        AlertDialog(
            onDismissRequest = { reminder = false },
            title = { Text(stringResource(R.string.incall_record_reminder_title)) },
            text = { Text(stringResource(R.string.incall_record_reminder_text)) },
            confirmButton = {
                TextButton(onClick = {
                    reminder = false
                    onEvent(InCallEvent.Record)
                }) { Text(stringResource(R.string.incall_record_confirm)) }
            },
            dismissButton = { TextButton(onClick = { reminder = false }) { Text(stringResource(R.string.incall_record_cancel)) } },
        )
    }
}

/** What the recording is doing, or why it stopped; nothing while idle. */
@Composable
internal fun RecordingBanner(
    status: CallRecordingStatus,
    onEvent: (InCallEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val text =
        when (status) {
            CallRecordingStatus.Idle -> return
            CallRecordingStatus.Connecting -> R.string.incall_recording_connecting
            CallRecordingStatus.Merging -> R.string.incall_recording_merging
            is CallRecordingStatus.Recording -> R.string.incall_recording_on
            is CallRecordingStatus.Failed -> messageOf(status.reason)
        }
    val failed = status is CallRecordingStatus.Failed
    Surface(
        shape = OpenCallerShape.card,
        color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(OpenCallerSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (failed) {
                TextButton(onClick = { onEvent(InCallEvent.RecordingErrorShown) }) { Text(stringResource(R.string.incall_recording_ok)) }
            }
        }
    }
}

@StringRes
private fun messageOf(reason: RecordingFailure): Int =
    when (reason) {
        RecordingFailure.UNAVAILABLE -> R.string.incall_recording_unavailable
        RecordingFailure.SUPPORTER_REQUIRED -> R.string.incall_recording_supporter
        RecordingFailure.NOT_SIGNED_IN -> R.string.incall_recording_signed_out
        RecordingFailure.NETWORK -> R.string.incall_recording_network
        RecordingFailure.CANNOT_DIAL -> R.string.incall_recording_cannot_dial
        RecordingFailure.LINE_NO_ANSWER -> R.string.incall_recording_no_answer
        RecordingFailure.MERGE_FAILED -> R.string.incall_recording_merge_failed
    }

@OpenCallerPreviews
@Composable
private fun RecordingBannerPreview() {
    OpenCallerTheme {
        RecordingBanner(status = CallRecordingStatus.Failed(RecordingFailure.MERGE_FAILED), onEvent = {})
    }
}
