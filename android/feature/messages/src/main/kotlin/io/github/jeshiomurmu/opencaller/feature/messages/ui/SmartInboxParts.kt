package io.github.jeshiomurmu.opencaller.feature.messages.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import io.github.jeshiomurmu.opencaller.core.ui.OtpCard
import io.github.jeshiomurmu.opencaller.feature.messages.R

/** The tabs as chips (with unread counts) and Clean up. */
@Composable
internal fun InboxTabs(
    state: ConversationsUiState,
    onEvent: (ConversationsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier.fillMaxWidth()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
            modifier = Modifier.weight(1f).padding(start = OpenCallerSpacing.screenPadding),
        ) {
            items(state.tabs, key = { it.tab }) { summary ->
                val name = stringResource(tabName(summary.tab))
                FilterChip(
                    selected = summary.tab == state.tab,
                    onClick = { onEvent(ConversationsEvent.SelectTab(summary.tab)) },
                    label = { Text(if (summary.unread > 0) stringResource(R.string.inbox_tab_unread, name, summary.unread) else name) },
                )
            }
        }
        TextButton(onClick = { onEvent(ConversationsEvent.AskClean) }) { Text(stringResource(R.string.inbox_clean)) }
    }
}

/** The OTP tab's card with the current code; Copy puts it on the clipboard, marked sensitive. */
@Composable
internal fun CurrentOtp(
    otp: OtpCardState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val expires =
        otp.expiresAtMillis?.let {
            stringResource(
                R.string.otp_notification_expires,
                DateFormat.getTimeFormat(context).format(it),
            )
        }
    OtpCard(code = otp.code, from = otp.from, expires = expires, onCopy = { copy(context, otp.code) }, modifier = modifier)
}

private fun copy(
    context: Context,
    code: String,
) {
    val clip = ClipData.newPlainText(context.getString(R.string.otp_notification_title), code)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

/** The inbox cleaner: what it would delete, the codes' auto-delete switch, Delete. */
@Composable
internal fun CleanDialog(
    plan: CleanPlan,
    autoDelete: Boolean,
    onEvent: (ConversationsEvent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onEvent(ConversationsEvent.CancelClean) },
        title = { Text(stringResource(R.string.inbox_clean_title)) },
        text = { CleanPlanText(plan = plan, autoDelete = autoDelete, onEvent = onEvent) },
        confirmButton = {
            TextButton(onClick = { onEvent(ConversationsEvent.ConfirmClean) }, enabled = plan.total > 0) {
                Text(stringResource(R.string.messages_delete))
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onEvent(ConversationsEvent.CancelClean) },
            ) { Text(stringResource(R.string.messages_keep)) }
        },
    )
}

@Composable
private fun CleanPlanText(
    plan: CleanPlan,
    autoDelete: Boolean,
    onEvent: (ConversationsEvent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
        Text(
            text =
                if (plan.total == 0) {
                    stringResource(R.string.inbox_clean_nothing)
                } else {
                    pluralStringResource(R.plurals.inbox_clean_body, plan.total, plan.total)
                },
            style = MaterialTheme.typography.bodyMedium,
        )
        plan.counts.forEach { (category, count) ->
            Text(text = "${stringResource(tabName(InboxTab.of(category)))}: $count", style = MaterialTheme.typography.bodyMedium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.inbox_auto_delete),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = autoDelete, onCheckedChange = { onEvent(ConversationsEvent.AutoDeleteCodes(it)) })
        }
    }
}

/** The string naming [tab]. */
internal fun tabName(tab: InboxTab): Int =
    when (tab) {
        InboxTab.PERSONAL -> R.string.inbox_tab_personal
        InboxTab.OTP -> R.string.inbox_tab_otp
        InboxTab.TRANSACTIONS -> R.string.inbox_tab_transactions
        InboxTab.BILLS -> R.string.inbox_tab_bills
        InboxTab.DELIVERIES -> R.string.inbox_tab_deliveries
        InboxTab.PROMOTIONS -> R.string.inbox_tab_promotions
        InboxTab.SPAM -> R.string.inbox_tab_spam
    }

@OpenCallerPreviews
@Composable
private fun InboxTabsPreview() {
    OpenCallerTheme {
        InboxTabs(
            state =
                ConversationsUiState(
                    loading = false,
                    tab = InboxTab.OTP,
                    tabs =
                        InboxTab.entries.map {
                            TabSummary(
                                it,
                                if (it ==
                                    InboxTab.OTP
                                ) {
                                    2
                                } else {
                                    0
                                },
                            )
                        },
                ),
            onEvent = {},
        )
    }
}

private val PREVIEW_PLAN = CleanPlan(mapOf(MessageCategory.OTP to 2, MessageCategory.PROMOTION to 3), listOf(1L, 2L, 3L, 4L, 5L))

@OpenCallerPreviews
@Composable
private fun CleanDialogPreview() {
    OpenCallerTheme { CleanDialog(PREVIEW_PLAN, autoDelete = true, onEvent = {}) }
}
