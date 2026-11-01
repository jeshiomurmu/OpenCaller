package io.github.jeshiomurmu.opencaller.feature.messages.ui

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.ui.EmptyState
import io.github.jeshiomurmu.opencaller.feature.messages.R

/** The conversations list, wired to its view model. */
@Composable
fun ConversationsRoute(
    onOpen: (Long) -> Unit,
    onNewMessage: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.onEvent(ConversationsEvent.Reload)
        onPauseOrDispose {}
    }
    Box(modifier = modifier.fillMaxSize()) {
        ConversationsScreen(state = state, onEvent = viewModel::onEvent, onOpen = onOpen)
        if (state.canRead) {
            FloatingActionButton(
                onClick = onNewMessage,
                modifier = Modifier.align(Alignment.BottomEnd).padding(OpenCallerSpacing.screenPadding),
            ) {
                Icon(painter = painterResource(OpenCallerIcons.Add), contentDescription = stringResource(R.string.messages_new))
            }
        }
    }
    state.confirmDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { viewModel.onEvent(ConversationsEvent.CancelDelete) },
            title = { Text(stringResource(R.string.messages_delete_title)) },
            text = { Text(stringResource(R.string.messages_delete_body, row.title)) },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.onEvent(ConversationsEvent.ConfirmDelete) },
                ) { Text(stringResource(R.string.messages_delete)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.onEvent(ConversationsEvent.CancelDelete) },
                ) { Text(stringResource(R.string.messages_keep)) }
            },
        )
    }
}

/** Every conversation, newest first: who, the latest message, when, and how many are unread. Long-press deletes. */
@Composable
fun ConversationsScreen(
    state: ConversationsUiState,
    onEvent: (ConversationsEvent) -> Unit,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        !state.canRead -> {
            EmptyState(icon = OpenCallerIcons.Messages, message = stringResource(R.string.messages_not_default), modifier = modifier)
        }

        state.failed && state.rows.isEmpty() -> {
            EmptyState(icon = OpenCallerIcons.Messages, message = stringResource(R.string.messages_failed), modifier = modifier)
        }

        state.loading -> {
            Box(modifier = modifier.fillMaxSize())
        }

        state.rows.isEmpty() -> {
            EmptyState(icon = OpenCallerIcons.Messages, message = stringResource(R.string.messages_empty), modifier = modifier)
        }

        else -> {
            LazyColumn(modifier = modifier.fillMaxSize()) {
                items(state.rows, key = { it.threadId }) { row ->
                    ConversationItem(
                        row = row,
                        onOpen = { onOpen(row.threadId) },
                        onLongPress = { onEvent(ConversationsEvent.AskDelete(row)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    row: ConversationRow,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
) {
    val unread = row.unread > 0
    val unreadLabel = if (unread) pluralStringResource(R.plurals.messages_unread, row.unread, row.unread) else null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = OpenCallerSpacing.minTouchTarget)
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = stringResource(R.string.messages_delete),
                    onLongClick = onLongPress,
                    onClick = onOpen,
                ).padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.s)
                .semantics { unreadLabel?.let { contentDescription = "${row.title}, $it" } },
    ) {
        Avatar(title = row.title, group = row.isGroup)
        ConversationText(row = row, unread = unread, modifier = Modifier.weight(1f))
        if (unread) Box(modifier = Modifier.size(OpenCallerSpacing.xs).background(MaterialTheme.colorScheme.primary, CircleShape))
    }
}

@Composable
private fun ConversationText(
    row: ConversationRow,
    unread: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (unread) FontWeight.Bold else null,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = DateUtils.getRelativeTimeSpanString(row.atMillis).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = snippetOf(row),
            style = MaterialTheme.typography.bodyMedium,
            color = if (unread) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (unread) FontWeight.SemiBold else null,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun snippetOf(row: ConversationRow): String {
    val text = row.snippet.ifBlank { if (row.hasImage) stringResource(R.string.messages_picture) else "" }
    return if (row.fromMe) stringResource(R.string.messages_snippet_you, text) else text
}

/** A circle with the first letter of [title], or the group icon. */
@Composable
internal fun Avatar(
    title: String,
    group: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(OpenCallerSpacing.avatar).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
    ) {
        val letter = title.firstOrNull { it.isLetter() }?.uppercaseChar()
        if (group || letter == null) {
            Icon(
                painter = painterResource(if (group) OpenCallerIcons.Community else OpenCallerIcons.Me),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            Text(
                text = letter.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

private const val PREVIEW_TIME = 1_760_000_000_000

private val PREVIEW_ROWS =
    listOf(
        ConversationRow(1, "Ana Silva", "See you at eight!", PREVIEW_TIME, unread = 2, isGroup = false, fromMe = false, hasImage = false),
        ConversationRow(2, "Ana, Ram, +1 555-000-3333", "", PREVIEW_TIME, unread = 0, isGroup = true, fromMe = true, hasImage = true),
    )

@OpenCallerPreviews
@Composable
private fun ConversationsScreenPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ConversationsScreen(state = ConversationsUiState(loading = false, rows = PREVIEW_ROWS), onEvent = {}, onOpen = {})
        }
    }
}
