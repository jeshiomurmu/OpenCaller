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

/** The actions of a row (docs/architecture.md §4.3: call back, message, save, block). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallActionsSheet(
    row: CallLogRow,
    actions: CallActions,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = OpenCallerSpacing.m)) {
            SheetTitle(row)
            // Each action closes the sheet first.
            ActionRows(row = row, actions = actions) { action ->
                {
                    onDismiss()
                    action()
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(row: CallLogRow) {
    Text(
        text = rowTitle(row),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(horizontal = OpenCallerSpacing.screenPadding).semantics { heading() },
    )
    row.display?.takeIf { row.name != null }?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = OpenCallerSpacing.screenPadding),
        )
    }
}

// Call, Message and the contact actions need a number to call back; Block a phone number not blocked yet.
@Composable
private fun ActionRows(
    row: CallLogRow,
    actions: CallActions,
    close: (() -> Unit) -> () -> Unit,
) {
    if (row.callBack != null) {
        ActionRow(OpenCallerIcons.Calls, R.string.calllog_action_call, close(actions.onCall))
        ActionRow(OpenCallerIcons.Message, R.string.calllog_action_message, close(actions.onMessage))
        if (row.contactId != null) {
            ActionRow(OpenCallerIcons.Contacts, R.string.calllog_action_open_contact, close(actions.onOpenContact))
        } else {
            ActionRow(OpenCallerIcons.PersonAdd, R.string.calllog_action_add_contact, close(actions.onAddContact))
        }
    }
    if (row.number != null && !row.flags.blocked) {
        ActionRow(OpenCallerIcons.Block, R.string.calllog_action_block, close(actions.onBlock))
    }
    ActionRow(OpenCallerIcons.Delete, R.string.calllog_action_delete, close(actions.onDelete))
}

@Composable
private fun ActionRow(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = OpenCallerSpacing.minTouchTarget)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.s),
    ) {
        Icon(painter = painterResource(icon), contentDescription = null)
        Text(text = stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}
