package io.github.jeshiomurmu.opencaller.feature.blocking

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.BlockRule
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleTarget
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import io.github.jeshiomurmu.opencaller.core.ui.RuleRow
import io.github.jeshiomurmu.opencaller.core.ui.labelRes

/** The rules editor, wired to its view model. */
@Composable
fun RulesRoute(
    modifier: Modifier = Modifier,
    viewModel: RulesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val text = state.message?.let { rulesMessageText(it) }
    val undo = stringResource(R.string.rules_undo)
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val blocked = message as? RulesMessage.Blocked
        val result =
            snackbar.showSnackbar(
                checkNotNull(text),
                actionLabel = undo.takeIf { blocked != null },
                duration = SnackbarDuration.Long,
            )
        if (blocked != null && result == SnackbarResult.ActionPerformed) viewModel.onEvent(RulesEvent.UndoBlock(blocked.ruleId))
        viewModel.onEvent(RulesEvent.MessageShown)
    }
    Box(modifier = modifier.fillMaxSize()) {
        RulesScreen(state = state, onEvent = viewModel::onEvent)
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

// docs/design.md §7: "Blocked +880 ••• 1234 · Undo".
@Composable
private fun rulesMessageText(message: RulesMessage): String =
    when (message) {
        is RulesMessage.Blocked -> stringResource(R.string.rules_blocked, message.masked)
        RulesMessage.Added -> stringResource(R.string.rules_added)
        RulesMessage.Failed -> stringResource(R.string.blocking_failed)
    }

/** Add a rule, test a number, and every saved rule with its switch, Test and Delete. */
@Composable
fun RulesScreen(
    state: RulesUiState,
    onEvent: (RulesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item { Title(stringResource(R.string.rules_title)) }
        item { DraftEditor(draft = state.draft, onEvent = onEvent) }
        item { NumberTester(state = state, onEvent = onEvent) }
        if (state.rules.isEmpty() && !state.loading) {
            item { EmptyRules() }
        }
        items(state.rules, key = BlockRule::id) { rule ->
            RuleRow(
                rule = rule,
                onEnabledChange = { onEvent(RulesEvent.SetEnabled(rule.id, it)) },
                onTest = { onEvent(RulesEvent.TestRule(rule)) },
                onDelete = { onEvent(RulesEvent.Delete(rule.id)) },
            )
        }
    }
}

@Composable
private fun EmptyRules() {
    Text(
        text = stringResource(R.string.rules_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(OpenCallerSpacing.screenPadding),
    )
}

@Composable
private fun DraftEditor(
    draft: RuleDraft,
    onEvent: (RulesEvent) -> Unit,
) {
    Section(stringResource(R.string.rules_add_title))
    Column(
        modifier = Modifier.padding(horizontal = OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
    ) {
        ChoiceRow(RuleType.entries, draft.type, { stringResource(it.labelRes()) }) { onEvent(RulesEvent.EditDraft(draft.copy(type = it))) }
        OutlinedTextField(
            value = draft.pattern,
            onValueChange = { onEvent(RulesEvent.EditDraft(draft.copy(pattern = it))) },
            label = { Text(text = stringResource(draft.type.hintRes())) },
            isError = draft.problem != null,
            supportingText = if (draft.problem != null) ({ Text(text = stringResource(draft.problem.messageRes())) }) else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = draft.type.keyboard(), imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onEvent(RulesEvent.SaveDraft) }),
            modifier = Modifier.fillMaxWidth(),
        )
        ChoiceRow(
            RuleAction.entries,
            draft.action,
            { stringResource(it.labelRes()) },
        ) { onEvent(RulesEvent.EditDraft(draft.copy(action = it))) }
        Button(onClick = { onEvent(RulesEvent.SaveDraft) }, enabled = !draft.saving) { Text(text = stringResource(R.string.rules_add)) }
    }
}

@Composable
private fun <T> ChoiceRow(
    choices: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
        choices.forEach { choice ->
            FilterChip(selected = choice == selected, onClick = { onSelect(choice) }, label = { Text(text = label(choice)) })
        }
    }
}

@Composable
private fun NumberTester(
    state: RulesUiState,
    onEvent: (RulesEvent) -> Unit,
) {
    Section(stringResource(R.string.test_title))
    Column(
        modifier = Modifier.padding(horizontal = OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
            OutlinedTextField(
                value = state.testInput,
                onValueChange = { onEvent(RulesEvent.EditTest(it)) },
                label = { Text(text = stringResource(R.string.test_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onEvent(RulesEvent.RunTest) }),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { onEvent(RulesEvent.RunTest) }, enabled = !state.testing) {
                Text(text = stringResource(R.string.test_run))
            }
        }
        state.testOutcome?.let { Text(text = testOutcomeText(it), style = MaterialTheme.typography.bodyLarge) }
    }
}

private fun RuleType.hintRes(): Int =
    when (this) {
        RuleType.EXACT -> R.string.rules_hint_exact
        RuleType.PREFIX -> R.string.rules_hint_prefix
        RuleType.COUNTRY -> R.string.rules_hint_country
        RuleType.REGEX -> R.string.rules_hint_regex
    }

private fun RuleType.keyboard(): KeyboardType =
    when (this) {
        RuleType.EXACT, RuleType.PREFIX -> KeyboardType.Phone
        RuleType.COUNTRY, RuleType.REGEX -> KeyboardType.Ascii
    }

@OpenCallerPreviews
@Composable
private fun RulesScreenPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            RulesScreen(
                state =
                    RulesUiState(
                        loading = false,
                        rules = listOf(BlockRule(1, RuleType.PREFIX, "+1555123", RuleAction.BLOCK, RuleTarget.CALLS, 0, true)),
                        testInput = "+15551230001",
                    ),
                onEvent = {},
            )
        }
    }
}
