package io.github.jeshiomurmu.opencaller.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme

/** "Wrongly marked as spam?", wired to its view model; [initialNumber] (E.164) fills the number field. */
@Composable
fun DisputeRoute(
    onSignIn: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    initialNumber: String? = null,
    viewModel: DisputeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DisputeScreen(
        state = state,
        initialNumber = initialNumber.orEmpty(),
        onSubmit = viewModel::submit,
        onSignIn = onSignIn,
        onDone = onDone,
        modifier = modifier,
    )
}

/** Stateless dispute form: the number, why the label is wrong, then "We'll review it". */
@Composable
fun DisputeScreen(
    state: DisputeUiState,
    initialNumber: String,
    onSubmit: (number: String, reason: String) -> Unit,
    onSignIn: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        Text(
            text = stringResource(R.string.dispute_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        when {
            state.loading -> {
                Unit
            }

            !state.serverConfigured -> {
                Text(text = stringResource(R.string.search_not_connected), style = MaterialTheme.typography.bodyLarge)
            }

            state.sentId != null -> {
                Text(text = stringResource(R.string.dispute_sent), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onDone, shape = OpenCallerShape.pill) { Text(text = stringResource(R.string.dispute_done)) }
            }

            !state.signedIn -> {
                Text(text = stringResource(R.string.dispute_needs_sign_in), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onSignIn, shape = OpenCallerShape.pill) { Text(text = stringResource(R.string.search_sign_in)) }
            }

            else -> {
                DisputeForm(state = state, initialNumber = initialNumber, onSubmit = onSubmit, onSignIn = onSignIn)
            }
        }
    }
}

@Composable
private fun DisputeForm(
    state: DisputeUiState,
    initialNumber: String,
    onSubmit: (number: String, reason: String) -> Unit,
    onSignIn: () -> Unit,
) {
    var number by rememberSaveable { mutableStateOf(initialNumber) }
    var reason by rememberSaveable { mutableStateOf("") }
    Text(
        text = stringResource(R.string.dispute_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = number,
        onValueChange = { number = it.take(MAX_NUMBER_INPUT) },
        label = { Text(text = stringResource(R.string.search_number)) },
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = reason,
        onValueChange = { reason = it.take(DisputeViewModel.MAX_REASON) },
        label = { Text(text = stringResource(R.string.dispute_reason)) },
        supportingText = { Text(text = stringResource(R.string.dispute_reason_hint)) },
        enabled = !state.busy,
        minLines = REASON_LINES,
        modifier = Modifier.fillMaxWidth(),
    )
    state.problem?.let { Text(text = disputeProblemText(it), style = MaterialTheme.typography.bodyLarge) }
    Button(
        onClick = { onSubmit(number, reason) },
        enabled = !state.busy && number.isNotBlank() && reason.isNotBlank(),
        shape = OpenCallerShape.pill,
    ) { Text(text = stringResource(R.string.dispute_send)) }
    if (state.problem == DisputeProblem.SignInNeeded) {
        TextButton(onClick = onSignIn) { Text(text = stringResource(R.string.search_sign_in)) }
    }
}

@Composable
private fun disputeProblemText(problem: DisputeProblem): String =
    stringResource(
        when (problem) {
            DisputeProblem.InvalidNumber -> R.string.search_invalid_number
            DisputeProblem.InvalidReason -> R.string.dispute_invalid_reason
            DisputeProblem.Offline -> R.string.search_offline
            DisputeProblem.TooMany -> R.string.dispute_too_many
            DisputeProblem.SignInNeeded -> R.string.search_sign_in_again
            DisputeProblem.ServerError -> R.string.search_server_error
        },
    )

private const val MAX_NUMBER_INPUT = 32
private const val REASON_LINES = 3

@OpenCallerPreviews
@Composable
private fun DisputeScreenPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            DisputeScreen(
                state = DisputeUiState(loading = false, serverConfigured = true, signedIn = true, problem = DisputeProblem.InvalidReason),
                initialNumber = "+12025550143",
                onSubmit = { _, _ -> },
                onSignIn = {},
                onDone = {},
            )
        }
    }
}
