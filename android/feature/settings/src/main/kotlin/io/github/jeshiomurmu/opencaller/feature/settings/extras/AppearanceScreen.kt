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
import io.github.jeshiomurmu.opencaller.core.model.ThemeMode
import io.github.jeshiomurmu.opencaller.core.ui.SupporterFeatureCard
import io.github.jeshiomurmu.opencaller.feature.settings.R

/** Settings → Appearance, wired to its view model. */
@Composable
fun AppearanceRoute(
    onOpenSupporter: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExtrasViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AppearanceScreen(state = state, onEvent = viewModel::onEvent, onOpenSupporter = onOpenSupporter, modifier = modifier)
}

/** Light, dark or system (docs/prd.md F-UT-07), and the Gold accents for supporters. */
@Composable
fun AppearanceScreen(
    state: ExtrasUiState,
    onEvent: (ExtrasEvent) -> Unit,
    onOpenSupporter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ExtrasPage(stringResource(R.string.appearance_title), stringResource(R.string.appearance_explainer), modifier) {
        listOf(
            ThemeMode.SYSTEM to R.string.appearance_system,
            ThemeMode.LIGHT to R.string.appearance_light,
            ThemeMode.DARK to R.string.appearance_dark,
        ).forEach { (mode, label) ->
            ChoiceRow(stringResource(label), selected = state.themeMode == mode, enabled = true) { onEvent(ExtrasEvent.Theme(mode)) }
        }
        Text(
            stringResource(R.string.appearance_gold_title),
            style = MaterialTheme.typography.titleMedium,
            color = OpenCallerTheme.semanticColors.gold,
        )
        SwitchRow(stringResource(R.string.appearance_gold), checked = state.goldTheme && state.entitled, enabled = state.entitled) {
            onEvent(ExtrasEvent.Gold(it))
        }
        if (!state.entitled) SupporterFeatureCard(onOpenSupporter = onOpenSupporter)
    }
}

@OpenCallerPreviews
@Composable
private fun AppearancePreview() {
    OpenCallerTheme { AppearanceScreen(ExtrasUiState(entitled = true, goldTheme = true), onEvent = {}, onOpenSupporter = {}) }
}
