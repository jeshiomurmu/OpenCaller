package io.github.jeshiomurmu.opencaller.feature.family

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme

/** Protection → Family protection, wired to its view model; [onSignIn] opens sign-in. */
@Composable
fun FamilyRoute(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FamilyViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Members, counts and alerts change on other phones: read them again whenever the screen comes back.
    LifecycleResumeEffect(state.signedIn) {
        if (state.signedIn) viewModel.onEvent(FamilyEvent.Refresh)
        onPauseOrDispose {}
    }
    FamilyScreen(state = state, onEvent = viewModel::onEvent, onSignIn = onSignIn, modifier = modifier)
}

/** The family protection screen: sign-in, start or join a group, the consent screen, or the group. */
@Composable
fun FamilyScreen(
    state: FamilyUiState,
    onEvent: (FamilyEvent) -> Unit,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
    ) {
        val preview = state.preview
        val group = state.group
        Heading(stringResource(R.string.family_title), large = true)
        if (state.busy || (state.loading && group == null)) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        when {
            !state.signedIn -> SignedOut(onSignIn)
            preview != null -> ConsentSection(preview, busy = state.busy, onEvent = onEvent)
            group != null -> GroupSection(group, state.invite, busy = state.busy, onEvent = onEvent)
            !state.loading -> NoGroup(busy = state.busy, onEvent = onEvent)
        }
    }
}

@Composable
internal fun Heading(
    text: String,
    large: Boolean = false,
) {
    Text(
        text,
        style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun SignedOut(onSignIn: () -> Unit) {
    Text(stringResource(R.string.family_intro))
    Text(stringResource(R.string.family_signed_out))
    Button(onClick = onSignIn) { Text(stringResource(R.string.family_sign_in)) }
}

@Composable
private fun NoGroup(
    busy: Boolean,
    onEvent: (FamilyEvent) -> Unit,
) {
    Text(stringResource(R.string.family_intro))
    HorizontalDivider()
    JoinForm(busy, onEvent)
    HorizontalDivider()
    CreateForm(busy, onEvent)
}

@Composable
private fun JoinForm(
    busy: Boolean,
    onEvent: (FamilyEvent) -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    Heading(stringResource(R.string.family_join_title))
    Text(stringResource(R.string.family_join_text), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = code,
        onValueChange = { code = it.take(MAX_CODE_INPUT) },
        label = { Text(stringResource(R.string.family_code)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onEvent(FamilyEvent.OpenInvite(code)) }, enabled = !busy && code.isNotBlank()) {
        Text(stringResource(R.string.family_continue))
    }
}

@Composable
private fun CreateForm(
    busy: Boolean,
    onEvent: (FamilyEvent) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    Heading(stringResource(R.string.family_create_title))
    Text(stringResource(R.string.family_create_text), style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = name,
        onValueChange = { name = oneLine(it) },
        label = { Text(stringResource(R.string.family_group_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    LabelField(label) { label = it }
    Button(onClick = { onEvent(FamilyEvent.Create(name, label)) }, enabled = !busy && name.isNotBlank() && label.isNotBlank()) {
        Text(stringResource(R.string.family_create))
    }
}

@Composable
internal fun LabelField(
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(oneLine(it)) },
        label = { Text(stringResource(R.string.family_your_label)) },
        supportingText = { Text(stringResource(R.string.family_your_label_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val MAX_CODE_INPUT = 12

@OpenCallerPreviews
@Composable
private fun NoGroupPreview() {
    OpenCallerTheme {
        FamilyScreen(state = FamilyUiState(signedIn = true, loading = false), onEvent = {}, onSignIn = {})
    }
}
