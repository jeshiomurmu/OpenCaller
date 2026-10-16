package io.github.jeshiomurmu.opencaller.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme

/** Settings home (Me tab until profiles arrive): entry points to each settings page. */
@Composable
fun SettingsScreen(
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(vertical = OpenCallerSpacing.m)) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = OpenCallerSpacing.screenPadding).semantics { heading() },
        )
        SettingsRow(
            title = stringResource(R.string.settings_permissions),
            summary = stringResource(R.string.settings_permissions_summary),
            onClick = onOpenPermissions,
        )
    }
}

@Composable
private fun SettingsRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = OpenCallerSpacing.minTouchTarget)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
    ) {
        Icon(painter = painterResource(OpenCallerIcons.Protection), contentDescription = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OpenCallerPreviews
@Composable
private fun SettingsScreenPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) { SettingsScreen(onOpenPermissions = {}) }
    }
}
