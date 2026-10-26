package io.github.jeshiomurmu.opencaller.feature.incall.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import io.github.jeshiomurmu.opencaller.feature.incall.InCallEvent
import io.github.jeshiomurmu.opencaller.feature.incall.InCallUiState
import io.github.jeshiomurmu.opencaller.feature.incall.InCallViewModel
import io.github.jeshiomurmu.opencaller.feature.incall.R
import kotlinx.coroutines.delay

/** How long "Call ended" stays before the screen closes. */
private const val ENDED_SCREEN_MILLIS = 1_200L

/** What the in-call screen asks of its activity. */
class InCallCallbacks(
    /** Open the dial pad for a second call. */
    val onAddCall: () -> Unit,
    /** Every call has ended: close. */
    val onFinished: () -> Unit,
    /** Whether the call plays through the earpiece (the screen then turns off near the ear). */
    val onEarpiece: (Boolean) -> Unit,
)

/** The in-call screen, wired to its view model. [showKeypadAtStart] opens the keypad (Telecom asked for it). */
@Composable
fun InCallRoute(
    callbacks: InCallCallbacks,
    modifier: Modifier = Modifier,
    showKeypadAtStart: Boolean = false,
    viewModel: InCallViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val now by viewModel.nowMillis.collectAsStateWithLifecycle()
    val askNames = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.onEvent(InCallEvent.Refresh) }
    LaunchedEffect(state.usesEarpiece) { callbacks.onEarpiece(state.usesEarpiece) }
    if (state.isOver) {
        LaunchedEffect(Unit) {
            delay(ENDED_SCREEN_MILLIS)
            callbacks.onFinished()
        }
    }
    InCallScreen(
        state = state,
        nowMillis = now,
        onEvent = viewModel::onEvent,
        onAddCall = callbacks.onAddCall,
        onShowSimNames = { askNames.launch(Manifest.permission.READ_PHONE_NUMBERS) },
        modifier = modifier,
        showKeypadAtStart = showKeypadAtStart,
    )
}

/** The in-call screen: a ringing call, the call in progress, the SIM chooser, or "Call ended". */
@Composable
fun InCallScreen(
    state: InCallUiState,
    nowMillis: Long,
    onEvent: (InCallEvent) -> Unit,
    onAddCall: () -> Unit,
    onShowSimNames: () -> Unit,
    modifier: Modifier = Modifier,
    showKeypadAtStart: Boolean = false,
) {
    var keypadOpen by rememberSaveable { mutableStateOf(showKeypadAtStart) }
    var replying by rememberSaveable { mutableStateOf(false) }
    var choosingAudio by rememberSaveable { mutableStateOf(false) }
    val actions =
        OngoingActions(onEvent, onKeypad = { keypadOpen = !keypadOpen }, onAudio = { choosingAudio = true }, onAddCall = onAddCall)
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        InCallContent(
            state = state,
            nowMillis = nowMillis,
            keypadOpen = keypadOpen,
            actions = actions,
            onShowSimNames = onShowSimNames,
            onReply = { replying = true },
        )
    }
    InCallSheets(
        state = state,
        replying = replying,
        choosingAudio = choosingAudio,
        onEvent = onEvent,
        onClose = {
            replying = false
            choosingAudio = false
        },
    )
}

// The SIM chooser, the call in progress, a ringing call, or "Call ended".
@Composable
private fun InCallContent(
    state: InCallUiState,
    nowMillis: Long,
    keypadOpen: Boolean,
    actions: OngoingActions,
    onShowSimNames: () -> Unit,
    onReply: () -> Unit,
) {
    Box(modifier = Modifier.safeDrawingPadding().padding(horizontal = OpenCallerSpacing.screenPadding)) {
        val primary = state.primary
        val ringing = state.ringing
        when {
            state.isOver -> {
                CallEnded(modifier = Modifier.align(Alignment.Center))
            }

            primary?.call?.status == CallStatus.SELECT_SIM -> {
                SimChooser(
                    view = primary,
                    onEvent = actions.onEvent,
                    onShowNames = onShowSimNames,
                )
            }

            primary != null -> {
                OngoingCall(
                    state = state,
                    primary = primary,
                    nowMillis = nowMillis,
                    keypadOpen = keypadOpen,
                    actions = actions,
                )
            }

            ringing != null -> {
                IncomingCall(view = ringing, nowMillis = nowMillis, onEvent = actions.onEvent, onReply = onReply)
            }
        }
    }
}

// Decline with a message (while a call rings) and the audio picker (during a call).
@Composable
private fun InCallSheets(
    state: InCallUiState,
    replying: Boolean,
    choosingAudio: Boolean,
    onEvent: (InCallEvent) -> Unit,
    onClose: () -> Unit,
) {
    val ringing = state.ringing
    if (replying && ringing != null) {
        QuickReplySheet(
            replies = state.repliesFor(ringing.call),
            onSend = { message ->
                onClose()
                onEvent(InCallEvent.DeclineWithMessage(ringing.call.id, message))
            },
            onDismiss = onClose,
        )
    }
    if (choosingAudio && state.primary != null) {
        AudioRouteSheet(
            audio = state.audio,
            onRoute = { routeId ->
                onClose()
                onEvent(InCallEvent.Route(routeId))
            },
            onDismiss = onClose,
        )
    }
}

@Composable
private fun CallEnded(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.incall_ended),
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@OpenCallerPreviews
@Composable
private fun InCallScreenPreview() {
    OpenCallerTheme {
        InCallScreen(
            state = InCallSamples.ringingAlone,
            nowMillis = InCallSamples.NOW,
            onEvent = {},
            onAddCall = {},
            onShowSimNames = {},
        )
    }
}
