package io.github.jeshiomurmu.opencaller.feature.family

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.jeshiomurmu.opencaller.core.model.FamilySharing

/** Asks before something that can't be undone (leaving, ending the group, removing a member). */
@Composable
internal fun Confirm(
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.family_cancel)) } },
    )
}

/** What a member shares, in one line for the admin. */
@StringRes
internal fun sharingText(sharing: FamilySharing): Int =
    when {
        sharing.otpAlerts && sharing.dailyCounts -> R.string.family_member_shares_both
        sharing.otpAlerts -> R.string.family_member_shares_alerts
        sharing.dailyCounts -> R.string.family_member_shares_counts
        else -> R.string.family_member_shares_nothing
    }

/** A date and time in the user's format. */
@Composable
internal fun dateTime(millis: Long): String =
    DateUtils.formatDateTime(LocalContext.current, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)
