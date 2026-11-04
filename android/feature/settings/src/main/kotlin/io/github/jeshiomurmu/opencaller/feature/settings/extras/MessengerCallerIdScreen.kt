package io.github.jeshiomurmu.opencaller.feature.settings.extras

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.ui.SupporterFeatureCard
import io.github.jeshiomurmu.opencaller.feature.settings.R

/** Settings → WhatsApp, Telegram and Signal calls, wired to its view model. */
@Composable
fun MessengerCallerIdRoute(
    onOpenSupporter: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExtrasViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onEvent(ExtrasEvent.Resumed) }
    MessengerCallerIdScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onOpenAccess = { context.startActivity(viewModel.notificationAccessIntent()) },
        onOpenSupporter = onOpenSupporter,
        modifier = modifier,
    )
}

/**
 * The explainer before notification access (docs/architecture.md §5): what is read (only these apps' call
 * notifications), what is shown (caller ID when the notification has a number), what never happens (nothing stored
 * or sent). Then the switch and the way to system settings.
 */
@Composable
fun MessengerCallerIdScreen(
    state: ExtrasUiState,
    onEvent: (ExtrasEvent) -> Unit,
    onOpenAccess: () -> Unit,
    onOpenSupporter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExtrasPage(stringResource(R.string.messenger_title), stringResource(R.string.messenger_explainer), modifier) {
        Text(stringResource(R.string.messenger_privacy), style = MaterialTheme.typography.bodyMedium)
        if (!state.entitled) SupporterFeatureCard(onOpenSupporter = onOpenSupporter)
        SwitchRow(
            stringResource(R.string.messenger_switch),
            checked = state.messengerCallerId && state.entitled,
            enabled = state.entitled,
        ) {
            onEvent(ExtrasEvent.Messenger(it))
        }
        if (state.messengerCallerId && state.entitled && !state.notificationAccess) {
            Text(
                stringResource(R.string.messenger_access_needed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onOpenAccess) { Text(stringResource(R.string.messenger_open_access)) }
        }
        Text(
            stringResource(R.string.messenger_no_number_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OpenCallerPreviews
@Composable
private fun MessengerCallerIdPreview() {
    OpenCallerTheme {
        MessengerCallerIdScreen(
            ExtrasUiState(entitled = true, messengerCallerId = true),
            onEvent = {},
            onOpenAccess = {},
            onOpenSupporter = {},
        )
    }
}
