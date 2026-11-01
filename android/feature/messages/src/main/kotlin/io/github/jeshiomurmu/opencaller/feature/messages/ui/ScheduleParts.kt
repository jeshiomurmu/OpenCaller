package io.github.jeshiomurmu.opencaller.feature.messages.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.ScheduledMessage
import io.github.jeshiomurmu.opencaller.core.model.SyncState
import io.github.jeshiomurmu.opencaller.feature.messages.R

/** The thread's scheduled messages: when, the text, Cancel. */
@Composable
internal fun ScheduledMessages(
    scheduled: List<ScheduledMessage>,
    onCancel: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = OpenCallerSpacing.screenPadding)) {
        scheduled.forEach { message ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
                Icon(
                    painterResource(OpenCallerIcons.Schedule),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.schedule_line, when_(message.sendAtMillis), message.body),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onCancel(message.id) }, modifier = Modifier.heightIn(min = OpenCallerSpacing.minTouchTarget)) {
                    Text(stringResource(R.string.schedule_cancel))
                }
            }
        }
    }
}

/** "Send later": the preset times. */
@Composable
internal fun ScheduleChooser(
    presets: List<SchedulePreset>,
    onPick: (Long) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.schedule_title)) },
        text = {
            Column {
                presets.forEach { preset ->
                    TextButton(onClick = { onPick(preset.atMillis) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${stringResource(presetName(preset.kind))} · ${when_(preset.atMillis)}",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Text(text = stringResource(R.string.schedule_about), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.messages_keep)) } },
    )
}

@Composable
private fun when_(atMillis: Long): String =
    DateUtils.formatDateTime(
        LocalContext.current,
        atMillis,
        DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY,
    )

private fun presetName(kind: PresetKind): Int =
    when (kind) {
        PresetKind.IN_AN_HOUR -> R.string.schedule_in_an_hour
        PresetKind.TONIGHT -> R.string.schedule_tonight
        PresetKind.TOMORROW_MORNING -> R.string.schedule_tomorrow_morning
        PresetKind.TOMORROW_EVENING -> R.string.schedule_tomorrow_evening
    }

private const val PREVIEW_AT = 1_760_000_000_000

@OpenCallerPreviews
@Composable
private fun ScheduledMessagesPreview() {
    OpenCallerTheme {
        ScheduledMessages(
            listOf(ScheduledMessage(1, "+15550001111", "Happy birthday!", PREVIEW_AT, null, SyncState.PENDING)),
            onCancel = {},
        )
    }
}
