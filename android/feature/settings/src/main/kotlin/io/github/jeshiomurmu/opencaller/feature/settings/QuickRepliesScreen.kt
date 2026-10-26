package io.github.jeshiomurmu.opencaller.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences

/** Settings → Quick replies, wired to its view model. */
@Composable
fun QuickRepliesRoute(
    modifier: Modifier = Modifier,
    viewModel: QuickRepliesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    QuickRepliesScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

/** The user's quick replies with Edit, Move up and Delete, and a field to write a new one. */
@Composable
fun QuickRepliesScreen(
    state: QuickRepliesUiState,
    onEvent: (QuickRepliesEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenCallerSpacing.screenPadding),
    ) {
        Text(
            text = stringResource(R.string.quick_replies_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(if (state.replies.isEmpty()) R.string.quick_replies_none else R.string.quick_replies_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.replies.forEachIndexed { index, reply ->
            ReplyRow(reply = reply, index = index, onEvent = onEvent)
            HorizontalDivider()
        }
        if (state.canAdd) {
            Editor(state = state, onEvent = onEvent)
        } else {
            Text(
                text =
                    pluralStringResource(
                        R.plurals.quick_replies_full,
                        UserPreferences.MAX_QUICK_REPLIES,
                        UserPreferences.MAX_QUICK_REPLIES,
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ReplyRow(
    reply: String,
    index: Int,
    onEvent: (QuickRepliesEvent) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = OpenCallerSpacing.minTouchTarget),
    ) {
        Text(text = reply, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = { onEvent(QuickRepliesEvent.Edit(index)) }) {
            Icon(painter = painterResource(OpenCallerIcons.Edit), contentDescription = stringResource(R.string.quick_replies_edit, reply))
        }
        if (index > 0) {
            IconButton(onClick = { onEvent(QuickRepliesEvent.MoveUp(index)) }) {
                Icon(
                    painter = painterResource(OpenCallerIcons.MoveUp),
                    contentDescription = stringResource(R.string.quick_replies_move_up, reply),
                )
            }
        }
        IconButton(onClick = { onEvent(QuickRepliesEvent.Delete(index)) }) {
            Icon(
                painter = painterResource(OpenCallerIcons.Delete),
                contentDescription = stringResource(R.string.quick_replies_delete, reply),
            )
        }
    }
}

@Composable
private fun ColumnScope.Editor(
    state: QuickRepliesUiState,
    onEvent: (QuickRepliesEvent) -> Unit,
) {
    OutlinedTextField(
        value = state.draft,
        onValueChange = { onEvent(QuickRepliesEvent.Draft(it)) },
        label = { Text(stringResource(if (state.editing == null) R.string.quick_replies_new else R.string.quick_replies_change)) },
        supportingText = {
            val problem = state.problem
            Text(
                if (problem !=
                    null
                ) {
                    stringResource(problem.messageRes())
                } else {
                    stringResource(R.string.quick_replies_length, state.draft.length, UserPreferences.MAX_QUICK_REPLY_LENGTH)
                },
            )
        },
        isError = state.problem != null,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs), modifier = Modifier.align(Alignment.End)) {
        if (state.editing != null) {
            TextButton(onClick = { onEvent(QuickRepliesEvent.CancelEdit) }) { Text(stringResource(R.string.quick_replies_cancel)) }
        }
        Button(onClick = { onEvent(QuickRepliesEvent.Save) }) {
            Text(stringResource(if (state.editing == null) R.string.quick_replies_add else R.string.quick_replies_save))
        }
    }
}

private fun QuickReplyProblem.messageRes(): Int =
    when (this) {
        QuickReplyProblem.EMPTY -> R.string.quick_replies_problem_empty
        QuickReplyProblem.DUPLICATE -> R.string.quick_replies_problem_duplicate
        QuickReplyProblem.FULL -> R.string.quick_replies_problem_full
    }

@OpenCallerPreviews
@Composable
private fun QuickRepliesScreenPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            QuickRepliesScreen(
                state = QuickRepliesUiState(replies = listOf("Can't talk now, call you later", "In a meeting")),
                onEvent = {},
            )
        }
    }
}
