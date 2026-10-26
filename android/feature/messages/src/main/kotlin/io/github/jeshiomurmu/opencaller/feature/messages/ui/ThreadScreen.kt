package io.github.jeshiomurmu.opencaller.feature.messages.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.MessageKey
import io.github.jeshiomurmu.opencaller.core.model.MessageKind
import io.github.jeshiomurmu.opencaller.core.model.MessagingSim
import io.github.jeshiomurmu.opencaller.core.ui.EmptyState
import io.github.jeshiomurmu.opencaller.feature.messages.R

/** One conversation, wired to its view model. [draft] fills the composer (a `sms_body` from another app). */
@Composable
fun ThreadRoute(
    threadId: Long,
    modifier: Modifier = Modifier,
    draft: String? = null,
    viewModel: ThreadViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
            if (uris.isNotEmpty()) viewModel.onEvent(ThreadEvent.AddPictures(uris.map { it.toString() }))
        }
    LaunchedEffect(threadId) { viewModel.onEvent(ThreadEvent.Open(threadId, draft)) }
    LifecycleResumeEffect(threadId) {
        viewModel.onEvent(ThreadEvent.Visible(true))
        onPauseOrDispose { viewModel.onEvent(ThreadEvent.Visible(false)) }
    }
    ThreadScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onAttach = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        modifier = modifier,
    )
    if (state.choosingSim) {
        SimChooser(sims = state.sims, onPick = {
            viewModel.onEvent(ThreadEvent.PickSim(it))
        }, onCancel = { viewModel.onEvent(ThreadEvent.CancelSim) })
    }
}

/** The people, the messages (newest at the bottom) and the composer. */
@Composable
fun ThreadScreen(
    state: ThreadUiState,
    onEvent: (ThreadEvent) -> Unit,
    onAttach: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
            modifier = Modifier.fillMaxWidth().padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.xs),
        ) {
            Avatar(title = state.title, group = state.isGroup)
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        HorizontalDivider()
        when {
            !state.canRead -> {
                EmptyState(
                    icon = OpenCallerIcons.Messages,
                    message = stringResource(R.string.messages_not_default),
                    modifier = Modifier.weight(1f),
                )
            }

            else -> {
                Messages(state = state, onEvent = onEvent, modifier = Modifier.weight(1f))
            }
        }
        if (state.failed) FailureLine(onDismiss = { onEvent(ThreadEvent.DismissFailure) })
        if (state.canRead) Composer(state = state, onEvent = onEvent, onAttach = onAttach)
    }
}

@Composable
private fun Messages(
    state: ThreadUiState,
    onEvent: (ThreadEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Reversed, so the list starts at the newest message and stays there as messages arrive.
    LazyColumn(
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
        modifier = modifier.fillMaxWidth().padding(horizontal = OpenCallerSpacing.screenPadding),
    ) {
        items(state.messages.asReversed(), key = { "${it.key.kind}/${it.key.id}" }) { item ->
            MessageBubble(
                item = item,
                showSender = state.isGroup,
                onRetry = { onEvent(ThreadEvent.Retry(item.key)) },
                onDownload = { onEvent(ThreadEvent.Download(item.key)) },
            )
        }
    }
}

@Composable
private fun Composer(
    state: ThreadUiState,
    onEvent: (ThreadEvent) -> Unit,
    onAttach: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(OpenCallerSpacing.xs)) {
        if (state.pictures.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
                items(state.pictures, key = { it }) { uri ->
                    PartImage(
                        uri = uri,
                        contentDescription = stringResource(R.string.messages_remove_picture),
                        onClick = { onEvent(ThreadEvent.RemovePicture(uri)) },
                        modifier = Modifier.size(PICKED_SIZE),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onAttach) {
                Icon(painter = painterResource(OpenCallerIcons.Image), contentDescription = stringResource(R.string.messages_attach))
            }
            OutlinedTextField(
                value = state.draft,
                onValueChange = { onEvent(ThreadEvent.Draft(it)) },
                placeholder = { Text(stringResource(R.string.messages_compose_hint)) },
                shape = OpenCallerShape.card,
                maxLines = MAX_LINES,
                modifier = Modifier.weight(1f),
            )
            if (state.sims.size > 1) SimButton(state = state, onEvent = onEvent)
            IconButton(onClick = { onEvent(ThreadEvent.Send) }, enabled = state.canSend) {
                Icon(painter = painterResource(OpenCallerIcons.Send), contentDescription = stringResource(R.string.messages_send))
            }
        }
    }
}

// The SIM the next message goes out with; tapping it switches to the next SIM.
@Composable
private fun SimButton(
    state: ThreadUiState,
    onEvent: (ThreadEvent) -> Unit,
) {
    val current = state.sims.firstOrNull { it.subscriptionId == state.simId }
    val next = state.sims[(state.sims.indexOf(current) + 1) % state.sims.size]
    TextButton(onClick = { onEvent(ThreadEvent.PickSim(next.subscriptionId)) }) {
        Text(text = current?.let { simLabel(it) } ?: stringResource(R.string.messages_sim_ask), maxLines = 1)
    }
}

@Composable
internal fun simLabel(sim: MessagingSim): String = sim.label.ifBlank { stringResource(R.string.messages_sim_slot, sim.slotIndex + 1) }

@Composable
private fun SimChooser(
    sims: List<MessagingSim>,
    onPick: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.messages_sim_title)) },
        text = {
            Column {
                sims.forEach { sim -> TextButton(onClick = { onPick(sim.subscriptionId) }) { Text(simLabel(sim)) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.messages_cancel)) } },
    )
}

@Composable
private fun FailureLine(onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = OpenCallerSpacing.screenPadding),
    ) {
        Text(
            text = stringResource(R.string.messages_action_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.messages_ok)) }
    }
}

private val PICKED_SIZE = OpenCallerSpacing.xxl + OpenCallerSpacing.m
private const val MAX_PICK = 5
private const val MAX_LINES = 5

private const val PREVIEW_TIME = 1_760_000_000_000

private fun previewItem(
    id: Long,
    outgoing: Boolean,
    body: String,
    status: MessageStatus,
) = ThreadItem(
    MessageKey(MessageKind.SMS, id),
    outgoing,
    sender = null,
    body = body,
    subject = null,
    atMillis = PREVIEW_TIME,
    status = status,
)

private val PREVIEW_THREAD =
    ThreadUiState(
        loading = false,
        title = "Ana Silva",
        messages =
            listOf(
                previewItem(1, outgoing = false, body = "Dinner at eight?", status = MessageStatus.NONE),
                previewItem(2, outgoing = true, body = "Sounds good, see you there!", status = MessageStatus.DELIVERED),
                previewItem(3, outgoing = true, body = "Shall I bring dessert?", status = MessageStatus.FAILED),
            ),
        draft = "On my way",
        sims =
            listOf(
                MessagingSim(subscriptionId = 1, label = "Work", slotIndex = 0),
                MessagingSim(subscriptionId = 2, label = "", slotIndex = 1),
            ),
        simId = 1,
    )

@OpenCallerPreviews
@Composable
private fun ThreadScreenPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ThreadScreen(state = PREVIEW_THREAD, onEvent = {}, onAttach = {})
        }
    }
}
