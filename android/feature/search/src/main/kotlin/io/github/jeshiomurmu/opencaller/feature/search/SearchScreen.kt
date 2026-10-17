package io.github.jeshiomurmu.opencaller.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.data.lookup.SearchQuota
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.SpamVerdict
import io.github.jeshiomurmu.opencaller.core.model.VerdictSource
import io.github.jeshiomurmu.opencaller.core.ui.CallerCard
import io.github.jeshiomurmu.opencaller.core.ui.CallerCardSurface
import io.github.jeshiomurmu.opencaller.core.ui.WhyButton
import io.github.jeshiomurmu.opencaller.core.ui.WhySheet
import io.github.jeshiomurmu.opencaller.core.ui.whyReasons

/** Number search, wired to its view model. */
@Composable
fun SearchRoute(
    navigation: SearchNavigation,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SearchScreen(state = state, onSearch = viewModel::search, navigation = navigation, modifier = modifier)
}

/** Stateless search screen: input, quota indicator, result card or one of the design.md §8 error states. */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onSearch: (String) -> Unit,
    navigation: SearchNavigation,
    modifier: Modifier = Modifier,
) {
    var explaining by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        Text(
            text = stringResource(R.string.search_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        when {
            state.loading -> {
                // Nothing to show until the state is loaded.
            }

            !state.serverConfigured -> Message(stringResource(R.string.search_not_connected))
            !state.signedIn -> SignInNeeded(navigation.onSignIn)
            else -> SearchForm(state = state, onSearch = onSearch, navigation = navigation, onWhy = { explaining = true })
        }
    }
    val found = state.found
    if (explaining && found != null) WhySheet(reasons = whyReasons(found.caller), onDismiss = { explaining = false })
}

@Composable
private fun SearchForm(
    state: SearchUiState,
    onSearch: (String) -> Unit,
    navigation: SearchNavigation,
    onWhy: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val search = { onSearch(input) }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it.take(MAX_INPUT) },
        label = { Text(text = stringResource(R.string.search_number)) },
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { search() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
        Button(
            onClick = search,
            enabled = !state.busy && input.isNotBlank() && state.quota?.remaining != 0,
            shape = OpenCallerShape.pill,
        ) { Text(text = stringResource(R.string.search_button)) }
        state.quota?.let { QuotaIndicator(it, modifier = Modifier.weight(1f)) }
    }
    Body(stringResource(R.string.search_privacy))
    state.problem?.let { problem -> ProblemMessage(problem, onRetry = search, onSignIn = navigation.onSignIn) }
    state.found?.let { found ->
        SearchResultCard(found = found, onWhy = onWhy, onDispute = {
            navigation.onDispute(
                found.caller.number
                    ?.e164
                    .orEmpty(),
            )
        })
    }
}

@Composable
private fun ProblemMessage(
    problem: SearchProblem,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
) {
    when (problem) {
        SearchProblem.InvalidNumber -> {
            Message(stringResource(R.string.search_invalid_number))
        }

        SearchProblem.Offline -> {
            Message(stringResource(R.string.search_offline))
        }

        SearchProblem.QuotaUsed -> {
            Message(pluralStringResource(R.plurals.search_quota_used, SEARCHES_PER_DAY, SEARCHES_PER_DAY))
        }

        SearchProblem.SignInNeeded -> {
            Message(stringResource(R.string.search_sign_in_again))
            TextButton(onClick = onSignIn) { Text(text = stringResource(R.string.search_sign_in)) }
        }

        SearchProblem.ServerError -> {
            Message(stringResource(R.string.search_server_error))
            TextButton(onClick = onRetry) { Text(text = stringResource(R.string.search_retry)) }
        }
    }
}

@Composable
private fun SignInNeeded(onSignIn: () -> Unit) {
    Body(stringResource(R.string.search_needs_sign_in))
    Button(onClick = onSignIn, shape = OpenCallerShape.pill) { Text(text = stringResource(R.string.search_sign_in)) }
}

@Composable
private fun Message(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Body(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val MAX_INPUT = 32
private const val SEARCHES_PER_DAY = 50

@OpenCallerPreviews
@Composable
private fun SearchScreenPreview() {
    val number = PhoneNumber("+12025550143")
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            SearchScreen(
                state =
                    SearchUiState(
                        loading = false,
                        serverConfigured = true,
                        signedIn = true,
                        quota = SearchQuota(limit = 50, remaining = 42, blockedUntil = null),
                        found =
                            SearchFound(
                                caller =
                                    CallerInfo(
                                        number = number,
                                        displayName = "Ana Loans",
                                        nameSource = NameSource.SELF_REGISTERED,
                                        spamVerdict =
                                            SpamVerdict(
                                                score = 0.8f,
                                                level = RiskLevel.LIKELY_SPAM,
                                                category = "loan",
                                                reportCount = 214,
                                                source = VerdictSource.SEARCH,
                                            ),
                                        regionCode = "US",
                                    ),
                                formattedNumber = "(202) 555-0143",
                                notSpamCount = 3,
                            ),
                    ),
                onSearch = {},
                navigation = SearchNavigation(onSignIn = {}, onDispute = {}),
            )
        }
    }
}

@OpenCallerPreviews
@Composable
private fun SearchQuotaUsedPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            SearchScreen(
                state =
                    SearchUiState(
                        loading = false,
                        serverConfigured = true,
                        signedIn = true,
                        quota = SearchQuota(limit = 50, remaining = 0, blockedUntil = 1L),
                        problem = SearchProblem.QuotaUsed,
                    ),
                onSearch = {},
                navigation = SearchNavigation(onSignIn = {}, onDispute = {}),
            )
        }
    }
}
