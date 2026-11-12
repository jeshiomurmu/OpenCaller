package io.github.jeshiomurmu.opencaller.feature.assistant

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.model.CallUrgency
import io.github.jeshiomurmu.opencaller.core.model.CallerDetails
import io.github.jeshiomurmu.opencaller.core.model.ScreenedCall
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEnd
import io.github.jeshiomurmu.opencaller.core.model.Speaker
import io.github.jeshiomurmu.opencaller.core.model.TranscriptLine

/** One screened call in the history; tapping it shows the conversation. */
@Composable
internal fun ScreenedCallCard(
    call: ScreenedCall,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable(call.id) { mutableStateOf(false) }
    Card(modifier = modifier.fillMaxWidth().clickable { open = !open }) {
        Column(modifier = Modifier.padding(OpenCallerSpacing.s), verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xxs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(call.caller?.e164 ?: stringResource(R.string.assistant_hidden_number), style = MaterialTheme.typography.titleSmall)
                    Text(
                        DateUtils.getRelativeTimeSpanString(call.startedAtMillis).toString() +
                            endText(call.end)?.let { " · " + stringResource(it) }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(painterResource(OpenCallerIcons.Delete), contentDescription = stringResource(R.string.assistant_delete))
                }
            }
            DetailsText(call.details)
            if (open) Transcript(call.transcript)
        }
    }
}

/** Name, reason and the urgent / likely-spam marks. */
@Composable
internal fun DetailsText(details: CallerDetails) {
    details.name?.let { Text(stringResource(R.string.assistant_live_name, it), style = MaterialTheme.typography.bodyMedium) }
    details.reason?.let { Text(stringResource(R.string.assistant_live_reason, it), style = MaterialTheme.typography.bodyMedium) }
    if (details.urgency == CallUrgency.HIGH) {
        Text(
            stringResource(R.string.assistant_urgent_label),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelLarge,
        )
    }
    if (details.likelySpam) {
        Text(
            stringResource(R.string.assistant_spam_label),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** The conversation, one line per row with who said it. */
@Composable
internal fun Transcript(lines: List<TranscriptLine>) {
    lines.forEach { line ->
        val who = stringResource(if (line.speaker == Speaker.CALLER) R.string.assistant_live_caller else R.string.assistant_live_assistant)
        Text("$who: ${line.text}", style = MaterialTheme.typography.bodySmall)
    }
}

/** How a call ended, as the history and the live screen say it. */
internal fun endText(end: ScreeningEnd?): Int? =
    when (end) {
        ScreeningEnd.CALL_BACK -> R.string.assistant_end_callback
        ScreeningEnd.SPAM -> R.string.assistant_end_spam
        ScreeningEnd.TIME_UP -> R.string.assistant_end_time
        ScreeningEnd.CALLER_HUNG_UP -> R.string.assistant_end_hung_up
        ScreeningEnd.DONE -> R.string.assistant_end_done
        ScreeningEnd.UNKNOWN, null -> null
    }
