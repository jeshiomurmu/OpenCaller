package io.github.jeshiomurmu.opencaller.feature.dialer

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.ui.DialPad
import io.github.jeshiomurmu.opencaller.core.ui.PermissionExplainer

/** Alpha of the Call button while there is no number. */
private const val DISABLED_ALPHA = 0.38f

/**
 * The dial pad, wired to its view model. [initialNumber] fills it once (a `tel:` link). The call permission is asked
 * for after its explainer, and only when OpenCaller is the default phone app.
 */
@Composable
fun DialPadRoute(
    initialNumber: String?,
    modifier: Modifier = Modifier,
    viewModel: DialPadViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val askToCall =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onEvent(DialPadEvent.PermissionResult(granted))
        }
    LaunchedEffect(initialNumber) { viewModel.onEvent(DialPadEvent.Prefill(initialNumber)) }
    if (state.explainingPermission) {
        PermissionExplainer(
            icon = OpenCallerIcons.Calls,
            title = stringResource(R.string.dialpad_permission_title),
            why = stringResource(R.string.dialpad_permission_why),
            never = stringResource(R.string.dialpad_permission_never),
            onContinue = { askToCall.launch(Manifest.permission.CALL_PHONE) },
            onNotNow = { viewModel.onEvent(DialPadEvent.CancelPermission) },
            modifier = modifier,
        )
    } else {
        DialPadScreen(
            state = state,
            onEvent = viewModel::onEvent,
            onPaste = { clipboardText(context)?.let { viewModel.onEvent(DialPadEvent.Paste(it)) } },
            modifier = modifier,
        )
    }
    state.simOptions?.let { options ->
        SimPickerSheet(options = options, onChoose = {
            viewModel.onEvent(DialPadEvent.ChooseSim(it))
        }, onDismiss = { viewModel.onEvent(DialPadEvent.DismissSims) })
    }
}

// The clipboard's text, read when the user asks to paste (the app is in front, as Android 10+ requires).
private fun clipboardText(context: Context): String? =
    context
        .getSystemService(ClipboardManager::class.java)
        ?.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()

/** Suggestions, the number, the keypad and Call. */
@Composable
fun DialPadScreen(
    state: DialPadUiState,
    onEvent: (DialPadEvent) -> Unit,
    modifier: Modifier = Modifier,
    onPaste: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = OpenCallerSpacing.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        Suggestions(
            suggestions = state.suggestions,
            onCall = { onEvent(DialPadEvent.CallSuggestion(it)) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        NumberRow(display = state.display, onDelete = {
            onEvent(DialPadEvent.Delete)
        }, onClear = { onEvent(DialPadEvent.Clear) }, onPaste = onPaste)
        problemText(state)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        DialPad(
            onKeyDown = { onEvent(DialPadEvent.Key(it)) },
            onKeyUp = {},
            longPressLabels = mapOf('1' to stringResource(R.string.dialpad_voicemail), '0' to stringResource(R.string.dialpad_plus)),
            onKeyLongPress = { onEvent(DialPadEvent.LongPress(it)) },
        )
        CallButton(enabled = state.number.isNotBlank(), onClick = { onEvent(DialPadEvent.Call) })
    }
}

@Composable
private fun problemText(state: DialPadUiState): String? =
    when (state.problem) {
        DialProblem.NO_PERMISSION -> stringResource(R.string.dialpad_permission_denied)
        DialProblem.CANT_CALL -> stringResource(R.string.dialpad_cant_call)
        DialProblem.CODE_REFUSED -> stringResource(R.string.dialpad_code_refused)
        null -> if (state.isDefaultPhoneApp) null else stringResource(R.string.dialpad_hand_off_note)
    }

@Composable
private fun Suggestions(
    suggestions: List<DialSuggestion>,
    onCall: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.Bottom, reverseLayout = false) {
        items(suggestions, key = { it.dialString }) { suggestion ->
            val label = suggestion.name ?: suggestion.display
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = OpenCallerSpacing.minTouchTarget)
                        .clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(R.string.dialpad_call_suggestion, label),
                        ) { onCall(suggestion.dialString) }
                        .padding(vertical = OpenCallerSpacing.xs),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (suggestion.name != null) {
                        Text(
                            text = suggestion.display,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(painter = painterResource(OpenCallerIcons.Calls), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun NumberRow(
    display: String,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onPaste: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(modifier = Modifier.size(OpenCallerSpacing.minTouchTarget))
        NumberText(display = display, onPaste = onPaste, modifier = Modifier.weight(1f))
        if (display.isEmpty()) {
            Spacer(modifier = Modifier.size(OpenCallerSpacing.minTouchTarget))
        } else {
            DeleteKey(onDelete = onDelete, onClear = onClear)
        }
    }
}

// The number in the dialer's mono font; a long press offers Paste.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NumberText(
    display: String,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Text(
            text = display.ifEmpty { stringResource(R.string.dialpad_hint) },
            style = OpenCallerTheme.extendedTypography.dialerNumber,
            color = if (display.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { menuOpen = true },
                        onLongClickLabel = stringResource(R.string.dialpad_paste),
                    ).semantics { liveRegion = LiveRegionMode.Polite },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.dialpad_paste)) },
                onClick = {
                    menuOpen = false
                    onPaste()
                },
            )
        }
    }
}

// Tap deletes one character, a long press clears the number.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeleteKey(
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(OpenCallerSpacing.minTouchTarget)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.dialpad_delete),
                    onLongClickLabel = stringResource(R.string.dialpad_clear),
                    onLongClick = onClear,
                    onClick = onDelete,
                ),
    ) {
        Icon(painter = painterResource(OpenCallerIcons.Backspace), contentDescription = stringResource(R.string.dialpad_delete))
    }
}

@Composable
private fun CallButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
        modifier =
            Modifier
                .padding(bottom = OpenCallerSpacing.m)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .alpha(if (enabled) 1f else DISABLED_ALPHA),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(OpenCallerSpacing.callAction).background(MaterialTheme.colorScheme.primary, CircleShape),
        ) {
            Icon(painter = painterResource(OpenCallerIcons.Calls), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        Text(text = stringResource(R.string.dialpad_call), style = MaterialTheme.typography.labelLarge)
    }
}

@OpenCallerPreviews
@Composable
private fun DialPadScreenPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            DialPadScreen(
                state =
                    DialPadUiState(
                        number = "726",
                        display = "726",
                        suggestions =
                            listOf(
                                DialSuggestion("Ram Kumar", "+12015550111", "(201) 555-0111"),
                                DialSuggestion(null, "+17265550100", "(726) 555-0100"),
                            ),
                        isDefaultPhoneApp = true,
                    ),
                onEvent = {},
            )
        }
    }
}
