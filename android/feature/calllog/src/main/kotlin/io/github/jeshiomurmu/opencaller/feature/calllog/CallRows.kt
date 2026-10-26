package io.github.jeshiomurmu.opencaller.feature.calllog

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.CallType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** One row: type icon, name or number, what happened and when, and Call. Tapping it opens the actions. */
@Composable
internal fun CallRow(
    row: CallLogRow,
    onCall: () -> Unit,
    onOpen: () -> Unit,
) {
    val title = rowTitle(row)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = OpenCallerSpacing.minTouchTarget)
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.calllog_more_actions), onClick = onOpen)
                .padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.xs),
    ) {
        Icon(painter = painterResource(typeIcon(row.type)), contentDescription = null, tint = typeTint(row))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = rowDetail(row),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.callBack != null) {
            IconButton(onClick = onCall) {
                Icon(
                    painter = painterResource(OpenCallerIcons.Calls),
                    contentDescription = stringResource(R.string.calllog_call_back, title),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** The name, else the number, else "Hidden number". */
@Composable
internal fun rowTitle(row: CallLogRow): String = row.name ?: row.display ?: stringResource(R.string.calllog_hidden)

// "Missed · 2 calls · 10:42 · Spam".
@Composable
private fun rowDetail(row: CallLogRow): String {
    val time = remember(row.atMillis) { TIME.format(Instant.ofEpochMilli(row.atMillis).atZone(ZoneId.systemDefault())) }
    val parts =
        buildList {
            add(stringResource(typeLabel(row.type)))
            if (row.count > 1) add(pluralStringResource(R.plurals.calllog_count, row.count, row.count))
            add(time)
            when {
                row.flags.spam && row.flags.blocked -> add(stringResource(R.string.calllog_spam))
                row.flags.spam -> add(stringResource(R.string.calllog_suspicious))
                row.flags.blocked && row.type != CallType.BLOCKED -> add(stringResource(R.string.calllog_blocked_label))
            }
        }
    return parts.joinToString(" · ")
}

@Composable
private fun typeTint(row: CallLogRow): Color =
    when {
        row.flags.spam || row.flags.blocked -> OpenCallerTheme.semanticColors.danger
        row.type == CallType.MISSED -> OpenCallerTheme.semanticColors.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

@DrawableRes
private fun typeIcon(type: CallType): Int =
    when (type) {
        CallType.OUTGOING -> OpenCallerIcons.CallMade
        CallType.MISSED, CallType.REJECTED -> OpenCallerIcons.CallMissed
        CallType.BLOCKED -> OpenCallerIcons.Block
        CallType.VOICEMAIL -> OpenCallerIcons.Voicemail
        CallType.INCOMING, CallType.ANSWERED_ELSEWHERE, CallType.UNKNOWN -> OpenCallerIcons.CallReceived
    }

@StringRes
private fun typeLabel(type: CallType): Int =
    when (type) {
        CallType.INCOMING -> R.string.calllog_type_incoming
        CallType.OUTGOING -> R.string.calllog_type_outgoing
        CallType.MISSED -> R.string.calllog_type_missed
        CallType.REJECTED -> R.string.calllog_type_rejected
        CallType.BLOCKED -> R.string.calllog_type_blocked
        CallType.VOICEMAIL -> R.string.calllog_type_voicemail
        CallType.ANSWERED_ELSEWHERE -> R.string.calllog_type_elsewhere
        CallType.UNKNOWN -> R.string.calllog_type_unknown
    }

/** "Today", "Yesterday", else the date in the user's format. */
@Composable
internal fun dayLabel(
    day: LocalDate,
    today: LocalDate?,
): String =
    when (day) {
        today -> stringResource(R.string.calllog_today)
        today?.minusDays(1) -> stringResource(R.string.calllog_yesterday)
        else -> remember(day) { DATE.format(day) }
    }

@Composable
internal fun DayHeader(
    day: LocalDate,
    today: LocalDate?,
) {
    Text(
        text = dayLabel(day, today),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.s).semantics { heading() },
    )
}

/** The string of [this] filter's chip. */
internal fun CallFilter.labelRes(): Int =
    when (this) {
        CallFilter.ALL -> R.string.calllog_filter_all
        CallFilter.MISSED -> R.string.calllog_filter_missed
        CallFilter.BLOCKED -> R.string.calllog_filter_blocked
        CallFilter.SPAM -> R.string.calllog_filter_spam
    }

private val TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
