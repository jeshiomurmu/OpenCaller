package io.github.jeshiomurmu.opencaller.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.R
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.AccessItemStatus
import io.github.jeshiomurmu.opencaller.feature.settings.SettingsScreen
import io.github.jeshiomurmu.opencaller.navigation.TopLevelDestination

/**
 * Calls tab until the call log arrives (Phase 6): offers the default phone app role when OpenCaller doesn't
 * hold it (TC-ONB-003), then the placeholder.
 */
@Composable
internal fun CallsScreen(
    defaultPhoneApp: AccessItemStatus?,
    onSetDefault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (defaultPhoneApp != null) {
            DefaultPhoneAppCard(
                canSetNow = defaultPhoneApp == AccessItemStatus.NOT_GRANTED,
                onSetDefault = onSetDefault,
                modifier = Modifier.padding(OpenCallerSpacing.screenPadding),
            )
        }
        PlaceholderScreen(TopLevelDestination.CALLS, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DefaultPhoneAppCard(
    canSetNow: Boolean,
    onSetDefault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = OpenCallerShape.card, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(OpenCallerSpacing.m), verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
            Text(
                text = stringResource(R.string.calls_default_app_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(if (canSetNow) R.string.calls_default_app_body else R.string.calls_default_app_later),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (canSetNow) {
                Button(
                    onClick = onSetDefault,
                    shape = OpenCallerShape.pill,
                ) { Text(text = stringResource(R.string.calls_default_app_action)) }
            }
        }
    }
}

/** Me tab until profiles arrive (Phase 8): a profile placeholder line, then settings. */
@Composable
internal fun MeScreen(
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(TopLevelDestination.ME.placeholder),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.m),
        )
        SettingsScreen(onOpenPermissions = onOpenPermissions)
    }
}

@OpenCallerPreviews
@Composable
private fun CallsScreenPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            CallsScreen(defaultPhoneApp = AccessItemStatus.NOT_GRANTED, onSetDefault = {})
        }
    }
}

@OpenCallerPreviews
@Composable
private fun MeScreenPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) { MeScreen(onOpenPermissions = {}) }
    }
}
