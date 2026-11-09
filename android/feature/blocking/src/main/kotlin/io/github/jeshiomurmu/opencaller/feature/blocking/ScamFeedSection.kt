package io.github.jeshiomurmu.opencaller.feature.blocking

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.Locale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.family.ScamFeed
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.model.ScamCard
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Scam cards for the user's country (docs/prd.md F-PR-15); [offline] when they come from the saved copy. */
data class ScamFeedUiState(
    val cards: List<ScamCard> = emptyList(),
    val offline: Boolean = false,
)

/** Loads the admin-curated scam cards of the phone's country, in the reader's language when there are any. */
@HiltViewModel
class ScamFeedViewModel
    @Inject
    constructor(
        private val feed: ScamFeed,
        private val region: HomeRegionProvider,
    ) : ViewModel() {
        private val state = MutableStateFlow(ScamFeedUiState())

        /** Screen state. */
        val uiState: StateFlow<ScamFeedUiState> = state.asStateFlow()

        /** Fetches the cards for a reader of [language]; without a known country there are none. */
        fun load(language: String) {
            val country = region.homeRegion() ?: return
            viewModelScope.launch {
                val result = feed.cards(country, language)
                // On failure (no server, nothing saved) the section stays as it was: hidden when empty.
                if (result is AppResult.Success) state.update { ScamFeedUiState(result.value.cards, result.value.offline) }
            }
        }
    }

/** The scam cards for the reader's language, loaded again when the language changes. */
@Composable
internal fun scamFeedState(viewModel: ScamFeedViewModel): ScamFeedUiState {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val language = Locale.current.toLanguageTag()
    LaunchedEffect(language) { viewModel.load(language) }
    return state
}

/** The scam cards in the Protection tab; nothing when there are none. */
@Composable
internal fun ScamFeedSection(state: ScamFeedUiState) {
    if (state.cards.isEmpty()) return
    val uri = LocalUriHandler.current
    Section(stringResource(R.string.scam_feed_title))
    if (state.offline) {
        Text(
            stringResource(R.string.scam_feed_offline),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = OpenCallerSpacing.screenPadding),
        )
    }
    state.cards.forEach { card ->
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.xs),
        ) {
            Column(
                modifier = Modifier.padding(OpenCallerSpacing.m),
                verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
            ) {
                Text(card.title, style = MaterialTheme.typography.titleSmall)
                Text(card.body, style = MaterialTheme.typography.bodyMedium)
                // The server accepts only https links without quotes or angle brackets.
                card.link?.takeIf { it.startsWith("https://") }?.let { link ->
                    TextButton(onClick = { uri.openUri(link) }) { Text(stringResource(R.string.scam_feed_more)) }
                }
            }
        }
    }
}
