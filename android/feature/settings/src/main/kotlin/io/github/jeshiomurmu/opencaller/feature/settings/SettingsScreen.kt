package io.github.jeshiomurmu.opencaller.feature.settings

import androidx.annotation.DrawableRes
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme

/**
 * Settings home (Me tab until profiles arrive): entry points to each settings page, and About with the app version.
 * Tapping the version [DEBUG_TAPS] times opens the debug screen (docs/architecture.md §12).
 */
@Composable
fun SettingsScreen(
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    versionName: String = "",
    onOpenDebug: () -> Unit = {},
    onOpenQuickReplies: () -> Unit = {},
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
        SettingsRow(
            title = stringResource(R.string.settings_quick_replies),
            summary = stringResource(R.string.settings_quick_replies_summary),
            onClick = onOpenQuickReplies,
            icon = OpenCallerIcons.Message,
        )
        AboutRow(versionName = versionName, onOpenDebug = onOpenDebug)
    }
}

/** Number of taps on the version that open the debug screen. */
const val DEBUG_TAPS = 7

@Composable
private fun AboutRow(
    versionName: String,
    onOpenDebug: () -> Unit,
) {
    var taps by rememberSaveable { mutableIntStateOf(0) }
    val left = DEBUG_TAPS - taps
    val hint = if (taps in 1 until DEBUG_TAPS) pluralStringResource(R.plurals.settings_debug_taps, left, left) else null
    SettingsRow(
        title = stringResource(R.string.settings_about),
        summary = hint ?: stringResource(R.string.settings_version, versionName),
        onClick = {
            taps += 1
            if (taps >= DEBUG_TAPS) {
                taps = 0
                onOpenDebug()
            }
        },
    )
}

@Composable
private fun SettingsRow(
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int = OpenCallerIcons.Protection,
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
        Icon(painter = painterResource(icon), contentDescription = null)
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
        Surface(color = MaterialTheme.colorScheme.surface) { SettingsScreen(onOpenPermissions = {}, versionName = "0.0.0") }
    }
}
