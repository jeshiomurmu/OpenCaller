package io.github.jeshiomurmu.opencaller.feature.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.FamilyInvitePreview
import io.github.jeshiomurmu.opencaller.core.model.FamilySharing

/**
 * The consent screen (TC-FAM-001): in plain words, what the admin will see (each item a switch, both off until the
 * member turns them on) and what they never see. Joining needs the member's own tick and their name in the group.
 */
@Composable
internal fun ConsentSection(
    preview: FamilyInvitePreview,
    busy: Boolean,
    onEvent: (FamilyEvent) -> Unit,
) {
    var alerts by rememberSaveable { mutableStateOf(false) }
    var counts by rememberSaveable { mutableStateOf(false) }
    var consented by rememberSaveable { mutableStateOf(false) }
    var label by rememberSaveable { mutableStateOf("") }
    val admin = preview.adminLabel
    Heading(stringResource(R.string.family_consent_title, preview.groupName))
    val people = pluralStringResource(R.plurals.family_consent_people, preview.maxMembers, preview.members, preview.maxMembers)
    Text(people, style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.family_consent_admin, admin))
    SharingSwitch(
        title = stringResource(R.string.family_consent_alerts),
        summary = stringResource(R.string.family_consent_alerts_text, admin),
        checked = alerts,
        onChange = { alerts = it },
    )
    SharingSwitch(
        title = stringResource(R.string.family_consent_counts),
        summary = stringResource(R.string.family_consent_counts_text, admin),
        checked = counts,
        onChange = { counts = it },
    )
    Heading(stringResource(R.string.family_consent_never_title, admin))
    Text(stringResource(R.string.family_consent_never))
    Text(stringResource(R.string.family_consent_change), style = MaterialTheme.typography.bodySmall)
    LabelField(label) { label = it }
    ConsentCheck(admin, consented) { consented = it }
    Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
        Button(
            onClick = { onEvent(FamilyEvent.Join(label, FamilySharing(otpAlerts = alerts, dailyCounts = counts), consented)) },
            enabled = !busy && consented && label.isNotBlank(),
        ) {
            Text(stringResource(R.string.family_join))
        }
        OutlinedButton(onClick = { onEvent(FamilyEvent.CancelJoin) }, enabled = !busy) {
            Text(stringResource(R.string.family_cancel))
        }
    }
}

@Composable
private fun ConsentCheck(
    admin: String,
    consented: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = consented, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
    ) {
        Checkbox(checked = consented, onCheckedChange = null)
        Text(stringResource(R.string.family_consent_check, admin))
    }
}

/** A labelled switch with a sentence saying exactly what it shares. */
@Composable
internal fun SharingSwitch(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = OpenCallerSpacing.m)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@OpenCallerPreviews
@Composable
private fun ConsentPreview() {
    OpenCallerTheme {
        FamilyScreen(
            state =
                FamilyUiState(
                    signedIn = true,
                    loading = false,
                    preview =
                        FamilyInvitePreview(
                            "ABCD2345",
                            "Rahman family",
                            "Rafi",
                            members = 2,
                            maxMembers = FamilySharing.MAX_MEMBERS,
                            expiresAtMillis = 0L,
                            consentVersion = FamilySharing.CONSENT_VERSION,
                        ),
                ),
            onEvent = {},
            onSignIn = {},
        )
    }
}
