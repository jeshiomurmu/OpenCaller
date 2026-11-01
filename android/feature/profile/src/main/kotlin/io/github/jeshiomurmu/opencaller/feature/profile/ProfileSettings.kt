package io.github.jeshiomurmu.opencaller.feature.profile

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.ProfileVisibility

/**
 * Who sees the name (docs/prd.md F-SO-01): each option says who, in plain words. The note says what visibility
 * doesn't change: people you call still see your number, and spam reports about your number stay.
 */
@Composable
fun VisibilityChoice(
    selected: ProfileVisibility,
    onSelect: (ProfileVisibility) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xxs)) {
        Text(
            text = stringResource(R.string.visibility_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        ProfileVisibility.entries.forEach { option ->
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = OpenCallerSpacing.minTouchTarget)
                        .selectable(selected = option == selected, role = Role.RadioButton, onClick = { onSelect(option) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = null)
                Column(modifier = Modifier.padding(start = OpenCallerSpacing.xs)) {
                    Text(text = stringResource(option.titleRes()), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = stringResource(option.explanationRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(text = stringResource(R.string.visibility_note), style = MaterialTheme.typography.bodySmall)
    }
}

/** Incognito (docs/prd.md F-SO-04): what it stops, and what it doesn't undo. */
@Composable
fun IncognitoSwitch(
    incognito: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = OpenCallerSpacing.minTouchTarget)
                .toggleable(value = incognito, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = stringResource(R.string.incognito_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(R.string.incognito_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = incognito, onCheckedChange = null)
    }
}

/** The two confirmations before an account is deleted (docs/prd.md F-SO-07), and the wait while it is. */
@Composable
fun DeleteAccountDialogs(
    step: DeleteStep,
    onEvent: (MyProfileEvent) -> Unit,
) {
    val cancel = { onEvent(MyProfileEvent.CancelDelete) }
    val next = { onEvent(MyProfileEvent.Delete) }
    when (step) {
        DeleteStep.NONE -> {}

        DeleteStep.FIRST_CONFIRM -> {
            ConfirmDialog(
                R.string.delete_first_title,
                R.string.delete_first_body,
                R.string.delete_continue,
                next,
                cancel,
            )
        }

        DeleteStep.SECOND_CONFIRM -> {
            ConfirmDialog(
                R.string.delete_second_title,
                R.string.delete_second_body,
                R.string.delete_confirm,
                next,
                cancel,
            )
        }

        DeleteStep.DELETING -> {
            DeletingDialog()
        }
    }
}

// The final step's button is in the error colour: it deletes the account.
@Composable
private fun ConfirmDialog(
    @StringRes title: Int,
    @StringRes body: Int,
    @StringRes confirm: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = stringResource(title)) },
        text = { Text(text = stringResource(body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                val color = if (confirm == R.string.delete_confirm) MaterialTheme.colorScheme.error else Color.Unspecified
                Text(text = stringResource(confirm), color = color)
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.delete_cancel)) } },
    )
}

@Composable
private fun DeletingDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(text = stringResource(R.string.delete_progress)) },
        text = { CircularProgressIndicator() },
        confirmButton = {},
    )
}

private fun ProfileVisibility.titleRes(): Int =
    when (this) {
        ProfileVisibility.EVERYONE -> R.string.visibility_everyone
        ProfileVisibility.REGISTERED_ONLY -> R.string.visibility_registered
        ProfileVisibility.NOBODY -> R.string.visibility_nobody
    }

private fun ProfileVisibility.explanationRes(): Int =
    when (this) {
        ProfileVisibility.EVERYONE -> R.string.visibility_everyone_explain
        ProfileVisibility.REGISTERED_ONLY -> R.string.visibility_registered_explain
        ProfileVisibility.NOBODY -> R.string.visibility_nobody_explain
    }

@OpenCallerPreviews
@Composable
private fun ProfileSettingsPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(OpenCallerSpacing.m)) {
                VisibilityChoice(selected = ProfileVisibility.REGISTERED_ONLY, onSelect = {})
                IncognitoSwitch(incognito = true, onChange = {})
            }
        }
    }
}
