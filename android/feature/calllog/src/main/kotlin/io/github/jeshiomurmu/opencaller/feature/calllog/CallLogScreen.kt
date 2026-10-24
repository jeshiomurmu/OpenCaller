package io.github.jeshiomurmu.opencaller.feature.calllog

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.CallType
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.ui.EmptyState
import io.github.jeshiomurmu.opencaller.core.ui.PermissionExplainer
import io.github.jeshiomurmu.opencaller.core.ui.PhoneLinks
import java.time.LocalDate

/**
 * The call log, wired to its view model: asks for the call log permission after an explainer (only shown while
 * OpenCaller is the default phone app), and reads the log again whenever the screen comes back.
 */
@Composable
fun CallLogRoute(
    navigation: CallLogNavigation,
    modifier: Modifier = Modifier,
    viewModel: CallLogViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val askForLog =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { viewModel.onEvent(CallLogEvent.Reload) }
    LifecycleResumeEffect(Unit) {
        viewModel.onEvent(CallLogEvent.Reload)
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CallLogEffect.OpenDialPad -> navigation.onDialPad(effect.number)
            }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    NoticeSnackbar(state.notice, snackbar, viewModel::onEvent)
    Box(modifier = modifier.fillMaxSize()) {
        when (state.access) {
            CallLogAccess.CHECKING -> {
                // Nothing shows until the access check is done.
            }

            CallLogAccess.NEEDS_PERMISSION -> {
                PermissionStep(onAllow = {
                    askForLog.launch(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG))
                })
            }

            CallLogAccess.READY -> {
                CallLogScreen(state = state, onEvent = viewModel::onEvent, navigation = navigation)
            }
        }
        FloatingActionButton(
            onClick = { navigation.onDialPad(null) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(OpenCallerSpacing.screenPadding),
        ) { Icon(painter = painterResource(OpenCallerIcons.Dialpad), contentDescription = stringResource(R.string.calllog_dial_pad)) }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
    state.confirmDelete?.let { row -> DeleteDialog(row = row, onEvent = viewModel::onEvent) }
}

// docs/design.md §7: "Blocked +880 ••• 1234 · Undo".
@Composable
private fun NoticeSnackbar(
    notice: CallLogNotice?,
    snackbar: SnackbarHostState,
    onEvent: (CallLogEvent) -> Unit,
) {
    val text =
        when (notice) {
            is CallLogNotice.Blocked -> stringResource(R.string.calllog_blocked, notice.masked)
            CallLogNotice.Failed -> stringResource(R.string.calllog_failed)
            null -> null
        }
    val undo = stringResource(R.string.calllog_undo)
    LaunchedEffect(notice) {
        if (notice == null || text == null) return@LaunchedEffect
        val blocked = notice is CallLogNotice.Blocked
        val result = snackbar.showSnackbar(text, actionLabel = undo.takeIf { blocked }, duration = SnackbarDuration.Long)
        onEvent(if (blocked && result == SnackbarResult.ActionPerformed) CallLogEvent.UndoBlock else CallLogEvent.DismissNotice)
    }
}

// The explainer first; after Not now, a short line with Allow.
@Composable
private fun PermissionStep(onAllow: () -> Unit) {
    var explaining by rememberSaveable { mutableStateOf(true) }
    if (explaining) {
        PermissionExplainer(
            icon = OpenCallerIcons.CallReceived,
            title = stringResource(R.string.calllog_permission_title),
            why = stringResource(R.string.calllog_permission_why),
            never = stringResource(R.string.calllog_permission_never),
            onContinue = onAllow,
            onNotNow = { explaining = false },
        )
    } else {
        EmptyState(
            icon = OpenCallerIcons.CallReceived,
            message = stringResource(R.string.calllog_permission_needed),
            actionLabel = stringResource(R.string.calllog_allow),
            onAction = onAllow,
        )
    }
}

/** Search, the filters and the calls by day. */
@Composable
fun CallLogScreen(
    state: CallLogUiState,
    onEvent: (CallLogEvent) -> Unit,
    navigation: CallLogNavigation,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<CallLogRow?>(null) }
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxSize()) {
        SearchField(query = state.query, onSearch = { onEvent(CallLogEvent.Search(it)) })
        Filters(selected = state.filter, onSelect = { onEvent(CallLogEvent.SetFilter(it)) })
        if (state.sections.isEmpty()) {
            val unfiltered = state.filter == CallFilter.ALL && state.query.isBlank()
            EmptyState(
                icon = OpenCallerIcons.Calls,
                message = stringResource(if (unfiltered) R.string.calllog_empty else R.string.calllog_empty_filtered),
            )
        } else {
            CallList(state = state, onCall = { onEvent(CallLogEvent.Call(it)) }, onOpen = { selected = it })
        }
    }
    selected?.let { row ->
        CallActionsSheet(
            row = row,
            actions =
                CallActions(
                    onCall = { onEvent(CallLogEvent.Call(row)) },
                    onMessage = { row.callBack?.let { PhoneLinks.message(context, it) } },
                    onOpenContact = { row.contactId?.let(navigation.onOpenContact) },
                    onAddContact = { row.callBack?.let(navigation.onAddContact) },
                    onBlock = { onEvent(CallLogEvent.Block(row)) },
                    onDelete = { onEvent(CallLogEvent.AskDelete(row)) },
                ),
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun SearchField(
    query: String,
    onSearch: (String) -> Unit,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onSearch,
        placeholder = { Text(stringResource(R.string.calllog_search_hint)) },
        leadingIcon = { Icon(painter = painterResource(OpenCallerIcons.Search), contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth().padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.xs),
    )
}

@Composable
private fun Filters(
    selected: CallFilter,
    onSelect: (CallFilter) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
        modifier = Modifier.fillMaxWidth().padding(horizontal = OpenCallerSpacing.screenPadding),
    ) {
        items(CallFilter.entries) { filter ->
            FilterChip(selected = filter == selected, onClick = { onSelect(filter) }, label = { Text(stringResource(filter.labelRes())) })
        }
    }
}

@Composable
private fun CallList(
    state: CallLogUiState,
    onCall: (CallLogRow) -> Unit,
    onOpen: (CallLogRow) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        state.sections.forEach { section ->
            item(key = "day/${section.day}") { DayHeader(day = section.day, today = state.today) }
            items(section.rows, key = { it.key }) { row -> CallRow(row = row, onCall = { onCall(row) }, onOpen = { onOpen(row) }) }
        }
    }
}

@Composable
private fun DeleteDialog(
    row: CallLogRow,
    onEvent: (CallLogEvent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onEvent(CallLogEvent.CancelDelete) },
        title = { Text(stringResource(R.string.calllog_delete_title)) },
        text = { Text(pluralStringResource(R.plurals.calllog_delete_body, row.count, rowTitle(row), row.count)) },
        confirmButton = {
            TextButton(
                onClick = { onEvent(CallLogEvent.ConfirmDelete) },
            ) { Text(stringResource(R.string.calllog_delete_confirm)) }
        },
        dismissButton = { TextButton(onClick = { onEvent(CallLogEvent.CancelDelete) }) { Text(stringResource(R.string.calllog_cancel)) } },
    )
}

@OpenCallerPreviews
@Composable
private fun CallLogScreenPreview() {
    val today = LocalDate.parse("2026-10-24")
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            CallLogScreen(
                state =
                    CallLogUiState(
                        access = CallLogAccess.READY,
                        today = today,
                        sections = listOf(CallLogSection(today, previewRows())),
                    ),
                onEvent = {},
                navigation = CallLogNavigation(onDialPad = {}, onOpenContact = {}, onAddContact = {}),
            )
        }
    }
}

// A missed call from a contact and a blocked spam call.
private fun previewRows(): List<CallLogRow> {
    val number = PhoneNumber("+12015550111")
    val row =
        CallLogRow(
            key = "a",
            ids = listOf(1, 2),
            name = "Ram Kumar",
            number = number,
            display = "(201) 555-0111",
            callBack = number.e164,
            type = CallType.MISSED,
            count = 2,
            atMillis = 0,
            flags = CallFlags(),
            contactId = 1,
        )
    val spam =
        row.copy(
            key = "b",
            name = null,
            display = "(202) 555-0199",
            type = CallType.BLOCKED,
            count = 1,
            flags = CallFlags(blocked = true, spam = true),
            contactId = null,
        )
    return listOf(row, spam)
}
