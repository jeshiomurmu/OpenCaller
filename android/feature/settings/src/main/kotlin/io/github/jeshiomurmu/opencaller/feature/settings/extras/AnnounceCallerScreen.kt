package io.github.jeshiomurmu.opencaller.feature.settings.extras

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.AnnounceCallerMode
import io.github.jeshiomurmu.opencaller.core.ui.SupporterFeatureCard
import io.github.jeshiomurmu.opencaller.feature.settings.R

/** Settings → Announce caller, wired to its view model. */
@Composable
fun AnnounceCallerRoute(
    onOpenSupporter: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExtrasViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AnnounceCallerScreen(state = state, onEvent = viewModel::onEvent, onOpenSupporter = onOpenSupporter, modifier = modifier)
}

/** Never, always, or with headphones only (docs/prd.md F-UT-02); a silenced phone stays silent. */
@Composable
fun AnnounceCallerScreen(
    state: ExtrasUiState,
    onEvent: (ExtrasEvent) -> Unit,
    onOpenSupporter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExtrasPage(stringResource(R.string.announce_title), stringResource(R.string.announce_explainer), modifier) {
        if (!state.entitled) SupporterFeatureCard(onOpenSupporter = onOpenSupporter)
        listOf(
            AnnounceCallerMode.OFF to R.string.announce_off,
            AnnounceCallerMode.ALWAYS to R.string.announce_always,
            AnnounceCallerMode.HEADSET_ONLY to R.string.announce_headset,
        ).forEach { (mode, label) ->
            val enabled = state.entitled || mode == AnnounceCallerMode.OFF
            ChoiceRow(
                stringResource(label),
                selected = state.announceMode == mode,
                enabled = enabled,
            ) { onEvent(ExtrasEvent.Announce(mode)) }
        }
        Text(
            stringResource(R.string.announce_quiet_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OpenCallerPreviews
@Composable
private fun AnnounceCallerPreview() {
    OpenCallerTheme {
        AnnounceCallerScreen(
            ExtrasUiState(entitled = true, announceMode = AnnounceCallerMode.HEADSET_ONLY),
            onEvent = {},
            onOpenSupporter = {},
        )
    }
}
