package io.github.jeshiomurmu.opencaller.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.BuildConfig
import io.github.jeshiomurmu.opencaller.R
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.AccessItemStatus
import io.github.jeshiomurmu.opencaller.feature.calllog.CallLogNavigation
import io.github.jeshiomurmu.opencaller.feature.calllog.CallLogRoute
import io.github.jeshiomurmu.opencaller.feature.settings.SettingsScreen
import io.github.jeshiomurmu.opencaller.navigation.TopLevelDestination

/**
 * Calls tab while OpenCaller isn't the default phone app: number search, the default phone app role (TC-ONB-003),
 * then the placeholder. The call log needs the role (Play policy for call log access).
 */
@Composable
internal fun CallsScreen(
    defaultPhoneApp: AccessItemStatus?,
    onSetDefault: () -> Unit,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize()) {
        SearchButton(onSearch)
        if (defaultPhoneApp != null) {
            DefaultAppCard(
                text = DefaultAppText(R.string.calls_default_app_title, R.string.calls_default_app_body, R.string.calls_default_app_later),
                canSetNow = defaultPhoneApp == AccessItemStatus.NOT_GRANTED,
                onSetDefault = onSetDefault,
                modifier = Modifier.padding(OpenCallerSpacing.screenPadding),
            )
        }
        PlaceholderScreen(TopLevelDestination.CALLS, modifier = Modifier.weight(1f))
    }
}

/**
 * Messages tab while OpenCaller isn't the default SMS app: the role offer, then the placeholder. Messages are read
 * and sent only as the default SMS app (Play policy for SMS access).
 */
@Composable
internal fun MessagesScreen(
    defaultSmsApp: AccessItemStatus?,
    onSetDefault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (defaultSmsApp != null) {
            DefaultAppCard(
                text =
                    DefaultAppText(
                        R.string.messages_default_app_title,
                        R.string.messages_default_app_body,
                        R.string.messages_default_app_later,
                    ),
                canSetNow = defaultSmsApp == AccessItemStatus.NOT_GRANTED,
                onSetDefault = onSetDefault,
                modifier = Modifier.padding(OpenCallerSpacing.screenPadding),
            )
        }
        PlaceholderScreen(TopLevelDestination.MESSAGES, modifier = Modifier.weight(1f))
    }
}

/** Calls tab of the default phone app: number search above the call log (with its dial pad button). */
@Composable
internal fun CallHistoryTab(
    onSearch: () -> Unit,
    navigation: CallLogNavigation,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SearchButton(onSearch)
        CallLogRoute(navigation = navigation, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SearchButton(onSearch: () -> Unit) {
    OutlinedButton(
        onClick = onSearch,
        shape = OpenCallerShape.pill,
        modifier = Modifier.fillMaxWidth().padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.s),
    ) { Text(text = stringResource(R.string.calls_search)) }
}

/** The texts of a default-app offer: its title, why, and what it says when the role can't be chosen yet. */
private class DefaultAppText(
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    @param:StringRes val later: Int,
)

@Composable
private fun DefaultAppCard(
    text: DefaultAppText,
    canSetNow: Boolean,
    onSetDefault: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = OpenCallerShape.card, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(OpenCallerSpacing.m), verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
            Text(
                text = stringResource(text.title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(if (canSetNow) text.body else text.later),
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

/** Me tab until profiles arrive (Phase 8): a profile placeholder line, the [account] section, then settings. */
@Composable
internal fun MeScreen(
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDebug: () -> Unit = {},
    onOpenQuickReplies: () -> Unit = {},
    account: @Composable () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            text = stringResource(TopLevelDestination.ME.placeholder),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.m),
        )
        account()
        SettingsScreen(
            onOpenPermissions = onOpenPermissions,
            versionName = BuildConfig.VERSION_NAME,
            onOpenDebug = onOpenDebug,
            onOpenQuickReplies = onOpenQuickReplies,
        )
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
private fun MessagesScreenPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            MessagesScreen(defaultSmsApp = AccessItemStatus.NOT_GRANTED, onSetDefault = {})
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
