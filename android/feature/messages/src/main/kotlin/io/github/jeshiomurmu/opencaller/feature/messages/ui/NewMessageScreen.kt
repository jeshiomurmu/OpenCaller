package io.github.jeshiomurmu.opencaller.feature.messages.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.feature.messages.R

/** Pick who a new message goes to, wired to its view model; [onOpenThread] opens the conversation. */
@Composable
fun NewMessageRoute(
    onOpenThread: (threadId: Long, recipients: List<String>) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NewMessageViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is NewMessageEffect.OpenThread -> onOpenThread(effect.threadId, effect.recipients)
            }
        }
    }
    NewMessageScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

/** The recipients as chips, a field for a name or number, matching contacts, and Next. */
@Composable
fun NewMessageScreen(
    state: NewMessageUiState,
    onEvent: (NewMessageEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
        modifier = modifier.fillMaxSize().padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.xs),
    ) {
        if (state.recipients.isNotEmpty()) RecipientChips(state.recipients, onRemove = { onEvent(NewMessageEvent.Remove(it)) })
        ToField(state = state, onEvent = onEvent)
        if (state.failed) {
            Text(
                text = stringResource(R.string.messages_action_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Suggestions(state.suggestions, onAdd = { onEvent(NewMessageEvent.Add(it)) }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun RecipientChips(
    recipients: List<Recipient>,
    onRemove: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
        items(recipients, key = { it.address }) { recipient ->
            InputChip(
                selected = false,
                onClick = { onRemove(recipient.address) },
                label = { Text(text = recipient.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

// The name-or-number field, Add for a typed number, and Next.
@Composable
private fun ToField(
    state: NewMessageUiState,
    onEvent: (NewMessageEvent) -> Unit,
) {
    OutlinedTextField(
        value = state.query,
        onValueChange = { onEvent(NewMessageEvent.Query(it)) },
        label = { Text(stringResource(R.string.messages_to)) },
        placeholder = { Text(stringResource(R.string.messages_to_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onEvent(NewMessageEvent.AddTyped) }),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
        if (state.canAddTyped) {
            TextButton(
                onClick = { onEvent(NewMessageEvent.AddTyped) },
            ) { Text(stringResource(R.string.messages_add_number, state.query.trim())) }
        }
        Button(
            onClick = { onEvent(NewMessageEvent.Next) },
            enabled = !state.opening && (state.recipients.isNotEmpty() || state.canAddTyped),
            shape = OpenCallerShape.pill,
        ) { Text(stringResource(R.string.messages_next)) }
    }
}

@Composable
private fun Suggestions(
    suggestions: List<Recipient>,
    onAdd: (Recipient) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        items(suggestions, key = { it.address + it.label }) { suggestion ->
            Text(
                text = suggestion.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = OpenCallerSpacing.minTouchTarget)
                        .clickable(role = Role.Button) { onAdd(suggestion) }
                        .padding(vertical = OpenCallerSpacing.s),
            )
        }
    }
}

@OpenCallerPreviews
@Composable
private fun NewMessageScreenPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            NewMessageScreen(
                state =
                    NewMessageUiState(
                        query = "Ra",
                        recipients = listOf(Recipient("+15550001111", "Ana Silva · (555) 000-1111")),
                        suggestions = listOf(Recipient("+15550002222", "Ram Kumar · (555) 000-2222")),
                    ),
                onEvent = {},
            )
        }
    }
}
