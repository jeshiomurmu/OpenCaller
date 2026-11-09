package io.github.jeshiomurmu.opencaller.feature.family

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.FamilyAlert
import io.github.jeshiomurmu.opencaller.core.model.FamilyAlertKind
import io.github.jeshiomurmu.opencaller.core.model.FamilyDay
import io.github.jeshiomurmu.opencaller.core.model.FamilyGroup
import io.github.jeshiomurmu.opencaller.core.model.FamilyInvite
import io.github.jeshiomurmu.opencaller.core.model.FamilyMember
import io.github.jeshiomurmu.opencaller.core.model.FamilyRole
import io.github.jeshiomurmu.opencaller.core.model.FamilySharing

/** The user's group: for a member, what they share; for the admin, invites, members with their counts, and alerts. */
@Composable
internal fun GroupSection(
    group: FamilyGroup,
    invite: FamilyInvite?,
    busy: Boolean,
    onEvent: (FamilyEvent) -> Unit,
) {
    Heading(group.name)
    if (group.isAdmin) {
        Text(stringResource(R.string.family_group_admin, group.memberCount, group.maxMembers))
        InviteSection(group, invite, busy, onEvent)
        HorizontalDivider()
        MembersSection(group, busy, onEvent)
        HorizontalDivider()
        AlertsSection(group.alerts)
    } else {
        Text(stringResource(R.string.family_group_member, group.adminLabel, group.myLabel))
        MySharing(group, busy, onEvent)
    }
    HorizontalDivider()
    LeaveButton(group, busy, onEvent)
}

@Composable
private fun MySharing(
    group: FamilyGroup,
    busy: Boolean,
    onEvent: (FamilyEvent) -> Unit,
) {
    val sharing = group.mySharing
    Heading(stringResource(R.string.family_my_sharing, group.adminLabel))
    SharingSwitch(
        title = stringResource(R.string.family_consent_alerts),
        summary = stringResource(R.string.family_share_alerts_summary),
        checked = sharing.otpAlerts,
        enabled = !busy,
        onChange = { onEvent(FamilyEvent.SetSharing(sharing.copy(otpAlerts = it))) },
    )
    SharingSwitch(
        title = stringResource(R.string.family_consent_counts),
        summary = stringResource(R.string.family_share_counts_summary),
        checked = sharing.dailyCounts,
        enabled = !busy,
        onChange = { onEvent(FamilyEvent.SetSharing(sharing.copy(dailyCounts = it))) },
    )
    Text(stringResource(R.string.family_never_short), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun InviteSection(
    group: FamilyGroup,
    invite: FamilyInvite?,
    busy: Boolean,
    onEvent: (FamilyEvent) -> Unit,
) {
    val context = LocalContext.current
    Heading(stringResource(R.string.family_invite_title))
    Text(stringResource(R.string.family_invite_text), style = MaterialTheme.typography.bodySmall)
    when {
        invite != null -> {
            val code = displayCode(invite.code)
            Text(stringResource(R.string.family_invite_code, code), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.family_invite_expires, dateTime(invite.expiresAtMillis)))
            val shareText = stringResource(R.string.family_invite_share_text, code)
            Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
                Button(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText)
                    context.startActivity(Intent.createChooser(send, null))
                }) { Text(stringResource(R.string.family_invite_share)) }
                OutlinedButton(onClick = { onEvent(FamilyEvent.InviteShown) }) { Text(stringResource(R.string.family_invite_done)) }
            }
        }

        group.hasRoom -> {
            Button(onClick = { onEvent(FamilyEvent.Invite) }, enabled = !busy) { Text(stringResource(R.string.family_invite_make)) }
        }

        else -> {
            Text(stringResource(R.string.family_full))
        }
    }
}

@Composable
private fun MembersSection(
    group: FamilyGroup,
    busy: Boolean,
    onEvent: (FamilyEvent) -> Unit,
) {
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    Heading(stringResource(R.string.family_members_title))
    Text(stringResource(R.string.family_member_you, group.myLabel))
    group.members.filter { it.role == FamilyRole.MEMBER }.forEach { member ->
        MemberRow(member, busy, onRemove = { removing = member.id })
    }
    group.members.firstOrNull { it.id == removing }?.let { member ->
        Confirm(
            text = stringResource(R.string.family_remove_confirm, member.label),
            confirm = stringResource(R.string.family_remove),
            onConfirm = {
                removing = null
                onEvent(FamilyEvent.Remove(member.id))
            },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun MemberRow(
    member: FamilyMember,
    busy: Boolean,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xxs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(member.label, style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(sharingText(member.sharing)), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onRemove, enabled = !busy) { Text(stringResource(R.string.family_remove)) }
        }
        if (member.sharing.dailyCounts) {
            if (member.days.isEmpty()) Text(stringResource(R.string.family_member_no_days), style = MaterialTheme.typography.bodySmall)
            member.days.forEach { DayLine(it) }
        }
    }
}

@Composable
private fun DayLine(day: FamilyDay) {
    Text(
        stringResource(R.string.family_member_day, day.day, day.blockedCalls, day.spamMessages),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun AlertsSection(alerts: List<FamilyAlert>) {
    Heading(stringResource(R.string.family_alerts_title))
    if (alerts.isEmpty()) Text(stringResource(R.string.family_alerts_empty), style = MaterialTheme.typography.bodySmall)
    alerts.forEach { alert ->
        Column {
            Text(
                stringResource(R.string.family_alert_line, alert.memberLabel, dateTime(alert.atMillis)),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(stringResource(alert.kind.text()), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LeaveButton(
    group: FamilyGroup,
    busy: Boolean,
    onEvent: (FamilyEvent) -> Unit,
) {
    var asking by rememberSaveable { mutableStateOf(false) }
    val action = stringResource(if (group.isAdmin) R.string.family_end else R.string.family_leave)
    OutlinedButton(onClick = { asking = true }, enabled = !busy) { Text(action) }
    if (asking) {
        Confirm(
            text = stringResource(if (group.isAdmin) R.string.family_end_confirm else R.string.family_leave_confirm, group.name),
            confirm = action,
            onConfirm = {
                asking = false
                onEvent(FamilyEvent.Leave)
            },
            onDismiss = { asking = false },
        )
    }
}

@OpenCallerPreviews
@Composable
private fun AdminPreview() {
    val member =
        FamilyMember(
            "m1",
            "Mum",
            FamilyRole.MEMBER,
            0L,
            FamilySharing(otpAlerts = true, dailyCounts = true),
            listOf(FamilyDay("2026-11-08", blockedCalls = 3, spamMessages = 2)),
        )
    OpenCallerTheme {
        Column {
            GroupSection(
                group =
                    FamilyGroup(
                        "g1",
                        "Rahman family",
                        "Rafi",
                        memberCount = 2,
                        maxMembers = FamilySharing.MAX_MEMBERS,
                        "Rafi",
                        FamilyRole.OWNER,
                        FamilySharing.NONE,
                        0L,
                        listOf(member),
                        listOf(FamilyAlert("a1", "m1", "Mum", FamilyAlertKind.OTP_DURING_CALL, 0L)),
                    ),
                invite = FamilyInvite("ABCD2345", 0L),
                busy = false,
                onEvent = {},
            )
        }
    }
}
