package io.github.jeshiomurmu.opencaller.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber

/** Sign-in, wired to its view model. [onDone] closes it (signed in, or "Not now"). */
@Composable
fun SignInRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SignInViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SignInScreen(state = state, onEvent = viewModel::onEvent, onDone = onDone, modifier = modifier)
}

/** Stateless sign-in screen: number, then the SMS code, then done. */
@Composable
fun SignInScreen(
    state: SignInUiState,
    onEvent: (SignInEvent) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        when {
            !state.serverConfigured -> NotConnected(onDone)
            state.step == SignInStep.NUMBER -> NumberStep(state, onEvent, onDone)
            state.step == SignInStep.CODE -> CodeStep(state, onEvent)
            else -> DoneStep(onDone)
        }
    }
}

@Composable
private fun NumberStep(
    state: SignInUiState,
    onEvent: (SignInEvent) -> Unit,
    onNotNow: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val send = { onEvent(SignInEvent.SendCode(input)) }
    Heading(stringResource(R.string.sign_in_title))
    Body(stringResource(R.string.sign_in_body))
    OutlinedTextField(
        value = input,
        onValueChange = { input = it.take(MAX_NUMBER_INPUT) },
        label = { Text(text = stringResource(R.string.sign_in_number)) },
        isError = state.problem != null,
        supportingText = { Text(text = state.problem?.let { problemText(it) } ?: stringResource(R.string.sign_in_number_hint)) },
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = send, enabled = !state.busy && input.isNotBlank(), shape = OpenCallerShape.pill) {
        Text(text = stringResource(R.string.sign_in_send_code))
    }
    TextButton(onClick = onNotNow) { Text(text = stringResource(R.string.sign_in_not_now)) }
    Body(stringResource(R.string.sign_in_privacy))
}

@Composable
private fun CodeStep(
    state: SignInUiState,
    onEvent: (SignInEvent) -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    val verify = { onEvent(SignInEvent.Verify(code)) }
    val problem = state.problem
    Heading(stringResource(R.string.sign_in_code_title))
    Body(stringResource(R.string.sign_in_code_body, state.display.orEmpty()))
    state.codeValidSeconds?.let { seconds ->
        val minutes = minutesFor(seconds)
        Body(pluralStringResource(R.plurals.sign_in_code_valid, minutes, minutes))
    }
    OutlinedTextField(
        value = code,
        onValueChange = { value -> code = value.filter(Char::isDigit).take(SignInViewModel.CODE_LENGTH) },
        label = { Text(text = stringResource(R.string.sign_in_code)) },
        isError = state.problem != null,
        supportingText = if (problem != null) ({ Text(text = problemText(problem)) }) else null,
        singleLine = true,
        enabled = !state.busy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { verify() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = verify, enabled = !state.busy && code.length == SignInViewModel.CODE_LENGTH, shape = OpenCallerShape.pill) {
        Text(text = stringResource(R.string.sign_in_verify))
    }
    TextButton(onClick = { onEvent(SignInEvent.ResendCode) }, enabled = !state.busy) {
        Text(text = stringResource(R.string.sign_in_resend))
    }
    TextButton(onClick = { onEvent(SignInEvent.ChangeNumber) }, enabled = !state.busy) {
        Text(text = stringResource(R.string.sign_in_change_number))
    }
}

@Composable
private fun DoneStep(onDone: () -> Unit) {
    Heading(stringResource(R.string.sign_in_done_title))
    Body(stringResource(R.string.sign_in_done_body))
    Button(onClick = onDone, shape = OpenCallerShape.pill) { Text(text = stringResource(R.string.sign_in_done)) }
}

@Composable
private fun NotConnected(onDone: () -> Unit) {
    Heading(stringResource(R.string.not_connected_title))
    Body(stringResource(R.string.not_connected_body))
    TextButton(onClick = onDone) { Text(text = stringResource(R.string.sign_in_back)) }
}

@Composable
private fun Heading(text: String) {
    Text(text = text, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Body(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val MAX_NUMBER_INPUT = 32

@OpenCallerPreviews
@Composable
private fun SignInNumberPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            SignInScreen(state = SignInUiState(problem = SignInProblem.InvalidNumber), onEvent = {}, onDone = {})
        }
    }
}

@OpenCallerPreviews
@Composable
private fun SignInCodePreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            SignInScreen(
                state =
                    SignInUiState(
                        step = SignInStep.CODE,
                        number = PhoneNumber("+12025550143"),
                        display = "(202) 555-0143",
                        codeValidSeconds = 300,
                        problem = SignInProblem.TooMany(retryAfterSeconds = 3_599),
                    ),
                onEvent = {},
                onDone = {},
            )
        }
    }
}
