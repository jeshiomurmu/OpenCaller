package io.github.jeshiomurmu.opencaller.feature.incall.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.telephony.incall.AudioRouteType
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import io.github.jeshiomurmu.opencaller.core.ui.DialPad
import io.github.jeshiomurmu.opencaller.feature.incall.CallView
import io.github.jeshiomurmu.opencaller.feature.incall.InCallEvent
import io.github.jeshiomurmu.opencaller.feature.incall.InCallUiState
import io.github.jeshiomurmu.opencaller.feature.incall.R

/**
 * The call in progress: the caller card and timer, a call waiting or on hold, then the controls (or the keypad) and
 * Hang up.
 */
@Composable
internal fun OngoingCall(
    state: InCallUiState,
    primary: CallView,
    nowMillis: Long,
    keypadOpen: Boolean,
    actions: OngoingActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m)) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
        ) {
            CallHeader(view = primary, nowMillis = nowMillis)
            state.ringing?.let { WaitingCall(view = it, onEvent = actions.onEvent) }
            state.held?.let { HeldCall(view = it, canSwap = state.swap != null, onSwap = { actions.onEvent(InCallEvent.Swap) }) }
        }
        if (keypadOpen) {
            InCallKeypad(onEvent = actions.onEvent, onClose = actions.onKeypad, modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            CallControls(state = state, actions = actions, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        RoundAction(
            icon = OpenCallerIcons.CallEnd,
            label = stringResource(R.string.incall_hang_up),
            container = OpenCallerTheme.semanticColors.danger,
            content = OpenCallerTheme.semanticColors.onDanger,
            onClick = { actions.onEvent(InCallEvent.HangUp(primary.call.id)) },
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = OpenCallerSpacing.m),
        )
    }
}

/** Mute, Keypad, Speaker (or Audio with a headset); Hold, Add call, Merge. */
@Composable
private fun CallControls(
    state: InCallUiState,
    actions: OngoingActions,
    modifier: Modifier = Modifier,
) {
    val onEvent = actions.onEvent
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m)) {
        Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.l)) {
            ControlButton(
                icon = OpenCallerIcons.MicOff,
                label = stringResource(R.string.incall_mute),
                checked = state.audio.muted,
                enabled = state.canMute,
                onClick = { onEvent(InCallEvent.ToggleMute) },
            )
            ControlButton(icon = OpenCallerIcons.Dialpad, label = stringResource(R.string.incall_keypad), onClick = actions.onKeypad)
            AudioControl(state = state, onEvent = onEvent, onAudio = actions.onAudio)
        }
        CallControlsSecondRow(state = state, actions = actions)
    }
}

// Hold, Add call, Merge.
@Composable
private fun CallControlsSecondRow(
    state: InCallUiState,
    actions: OngoingActions,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.l)) {
        ControlButton(
            icon = OpenCallerIcons.Hold,
            label = stringResource(R.string.incall_hold),
            checked = state.primary?.call?.status == CallStatus.HOLDING,
            enabled = state.canHold,
            onClick = { actions.onEvent(InCallEvent.ToggleHold) },
        )
        ControlButton(
            icon = OpenCallerIcons.AddCall,
            label = stringResource(R.string.incall_add_call),
            enabled = state.canAddCall,
            onClick = actions.onAddCall,
        )
        ControlButton(
            icon = OpenCallerIcons.Merge,
            label = stringResource(R.string.incall_merge),
            enabled = state.merge != null,
            onClick = { actions.onEvent(InCallEvent.Merge) },
        )
    }
}

// Speaker toggles between loudspeaker and earpiece; with a headset connected, Audio opens the picker instead.
@Composable
private fun AudioControl(
    state: InCallUiState,
    onEvent: (InCallEvent) -> Unit,
    onAudio: () -> Unit,
) {
    val audio = state.audio
    if (audio.hasExternalRoutes) {
        ControlButton(icon = routeIcon(audio.current?.type), label = stringResource(R.string.incall_audio), onClick = onAudio)
        return
    }
    val speakerOn = audio.current?.type == AudioRouteType.SPEAKER
    val target = audio.available.firstOrNull { it.type == if (speakerOn) AudioRouteType.EARPIECE else AudioRouteType.SPEAKER }
    ControlButton(
        icon = OpenCallerIcons.Speaker,
        label = stringResource(R.string.incall_speaker),
        checked = speakerOn,
        enabled = target != null,
        onClick = { target?.let { onEvent(InCallEvent.Route(it.id)) } },
    )
}

/** The other call, on hold, with Swap when the calls can be swapped. */
@Composable
private fun HeldCall(
    view: CallView,
    canSwap: Boolean,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = OpenCallerShape.card, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(modifier = Modifier.padding(OpenCallerSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.incall_held_call, titleOf(view)),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (canSwap) {
                TextButton(onClick = onSwap) { Text(stringResource(R.string.incall_swap)) }
            }
        }
    }
}

/** The keypad during a call: each key sends its tone for as long as it is pressed; the typed keys show above. */
@Composable
private fun InCallKeypad(
    onEvent: (InCallEvent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        Text(text = typed, style = OpenCallerTheme.extendedTypography.dialerNumber, maxLines = 1, overflow = TextOverflow.Ellipsis)
        DialPad(
            onKeyDown = { key ->
                typed += key
                onEvent(InCallEvent.KeyDown(key))
            },
            onKeyUp = { onEvent(InCallEvent.KeyUp) },
        )
        TextButton(onClick = onClose) { Text(stringResource(R.string.incall_hide_keypad)) }
    }
}

@OpenCallerPreviews
@Composable
private fun OngoingCallPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            OngoingCall(
                state = InCallSamples.twoCalls,
                primary = InCallSamples.contact,
                nowMillis = InCallSamples.NOW,
                keypadOpen = false,
                actions = OngoingActions(onEvent = {}, onKeypad = {}, onAudio = {}, onAddCall = {}),
            )
        }
    }
}
