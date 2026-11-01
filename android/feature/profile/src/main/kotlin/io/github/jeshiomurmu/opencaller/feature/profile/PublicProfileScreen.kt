package io.github.jeshiomurmu.opencaller.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.PublicProfile
import io.github.jeshiomurmu.opencaller.core.ui.SupporterBadgeLabel

/** Another number's profile, wired to its view model; [e164] comes from search. */
@Composable
fun PublicProfileRoute(
    e164: String,
    modifier: Modifier = Modifier,
    viewModel: PublicProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(e164) { viewModel.open(e164) }
    PublicProfileScreen(state = state, onAskForName = viewModel::askForName, modifier = modifier)
}

/**
 * What the user may see of the profile. The screen says the owner may see the visit unless the user is incognito
 * (docs/prd.md F-SO-02); without a name it offers a contact request and says what asking shares.
 */
@Composable
fun PublicProfileScreen(
    state: PublicProfileUiState,
    onAskForName: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val profile = state.profile
        when {
            state.loading -> {
                CircularProgressIndicator()
            }

            profile == null -> {
                ProblemLine(state.problem)
            }

            else -> {
                ProfilePicture(picture = state.picture, description = stringResource(R.string.profile_picture))
                Text(
                    text = profile.name ?: stringResource(R.string.public_profile_no_name),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(text = profile.number.e164, style = MaterialTheme.typography.bodyLarge)
                profile.badge?.let { SupporterBadgeLabel(it) }
                if (profile.name == null) AskForName(state, onAskForName)
            }
        }
        Text(text = stringResource(R.string.public_profile_visit_note), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AskForName(
    state: PublicProfileUiState,
    onAskForName: () -> Unit,
) {
    if (state.requestSent) {
        Text(text = stringResource(R.string.public_profile_request_sent), style = MaterialTheme.typography.bodyLarge)
    } else {
        Text(text = stringResource(R.string.public_profile_request_explain), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onAskForName, enabled = !state.requesting) { Text(text = stringResource(R.string.public_profile_request)) }
        ProblemLine(state.problem)
    }
}

@OpenCallerPreviews
@Composable
private fun PublicProfileScreenPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            val number = PhoneNumber("+12015550123")
            PublicProfileScreen(
                state = PublicProfileUiState(number = number, loading = false, profile = PublicProfile(number, null, null, null)),
                onAskForName = {},
            )
        }
    }
}
