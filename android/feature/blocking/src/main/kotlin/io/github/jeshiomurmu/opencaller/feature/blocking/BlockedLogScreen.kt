package io.github.jeshiomurmu.opencaller.feature.blocking

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.ui.EmptyState
import io.github.jeshiomurmu.opencaller.core.ui.ReportSheet
import io.github.jeshiomurmu.opencaller.core.ui.WhySheet
import io.github.jeshiomurmu.opencaller.core.ui.R as UiR

/** The blocked-calls log, wired to its view model. */
@Composable
fun BlockedLogRoute(
    modifier: Modifier = Modifier,
    viewModel: BlockedLogViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val text = state.message?.let { messageText(it) }
    LaunchedEffect(text) {
        if (text != null) {
            snackbar.showSnackbar(text)
            viewModel.onEvent(BlockedLogEvent.MessageShown)
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        BlockedLogScreen(state = state, onEvent = viewModel::onEvent)
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
    state.reporting?.let { call ->
        ReportSheet(
            number = call.display.orEmpty(),
            signedIn = state.signedIn,
            onSubmit = { viewModel.onEvent(BlockedLogEvent.SubmitReport(it)) },
            onDismiss = { viewModel.onEvent(BlockedLogEvent.DismissSheet) },
        )
    }
    state.explaining?.let { call -> WhySheet(reasons = call.why, onDismiss = { viewModel.onEvent(BlockedLogEvent.DismissSheet) }) }
}

@Composable
private fun messageText(message: BlockedLogMessage): String =
    when (message) {
        is BlockedLogMessage.Unblocked -> {
            stringResource(R.string.log_unblocked, message.masked)
        }

        is BlockedLogMessage.Reported -> {
            stringResource(
                if (message.blocked) R.string.report_done_blocked else R.string.report_done,
                message.masked,
            )
        }

        BlockedLogMessage.Failed -> {
            stringResource(R.string.blocking_failed)
        }
    }

/**
 * Blocked and silenced calls, newest first; every call from a known number can be unblocked in one tap or reported,
 * and every call says why it was blocked.
 */
@Composable
fun BlockedLogScreen(
    state: BlockedLogUiState,
    onEvent: (BlockedLogEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.calls.isEmpty() && !state.loading) {
        EmptyState(icon = OpenCallerIcons.Protection, message = stringResource(R.string.log_empty), modifier = modifier)
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        item { Title(stringResource(R.string.log_title)) }
        items(state.calls, key = BlockedCall::eventId) { call -> BlockedCallRow(call = call, onEvent = onEvent) }
    }
}

@Composable
private fun BlockedCallRow(
    call: BlockedCall,
    onEvent: (BlockedLogEvent) -> Unit,
) {
    val context = LocalContext.current
    val time =
        DateUtils.formatDateTime(
            context,
            call.atMillis,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        )
    val what = stringResource(if (call.decision == Decision.SILENCE) R.string.log_silenced else R.string.log_blocked)
    val why = call.reason.logReasonRes()?.let { stringResource(it) }
    val separator = stringResource(UiR.string.risk_banner_separator)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = OpenCallerSpacing.minTouchTarget)
                .padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.xs),
    ) {
        TextPair(
            title = call.display ?: stringResource(R.string.log_hidden),
            summary = listOfNotNull(what, why, time).joinToString(separator),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xxs, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { onEvent(BlockedLogEvent.Explain(call)) }) { Text(text = stringResource(R.string.log_why)) }
            if (call.number != null) {
                TextButton(onClick = { onEvent(BlockedLogEvent.Report(call)) }) { Text(text = stringResource(R.string.log_report)) }
                TextButton(onClick = { onEvent(BlockedLogEvent.Unblock(call)) }) { Text(text = stringResource(R.string.log_unblock)) }
            }
        }
    }
}

@OpenCallerPreviews
@Composable
private fun BlockedLogScreenPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            BlockedLogScreen(
                state =
                    BlockedLogUiState(
                        loading = false,
                        calls =
                            listOf(
                                BlockedCall(1, PhoneNumber("+15551230001"), "+1 555-123-0001", Decision.BLOCK, ScreeningReason.RULE, 0),
                                BlockedCall(2, null, null, Decision.BLOCK, ScreeningReason.HIDDEN, 0),
                                BlockedCall(
                                    eventId = 3,
                                    number = PhoneNumber("+15551230002"),
                                    display = "+1 555-123-0002",
                                    decision = Decision.BLOCK,
                                    reason = ScreeningReason.NETWORK,
                                    atMillis = 0,
                                    spamScore = 0.9f,
                                ),
                            ),
                    ),
                onEvent = {},
            )
        }
    }
}
