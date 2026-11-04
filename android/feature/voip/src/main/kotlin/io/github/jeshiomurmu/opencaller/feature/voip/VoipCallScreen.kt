package io.github.jeshiomurmu.opencaller.feature.voip

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.ui.CallControlButton
import io.github.jeshiomurmu.opencaller.core.ui.CallRoundAction
import io.github.jeshiomurmu.opencaller.core.ui.MutedClip
import java.io.File

/**
 * The app-call screen: who it is (and their video caller ID, muted, while it rings in), where the call is, and the
 * call buttons the phone-call screen uses too.
 */
@Composable
fun VoipCallScreen(
    state: AppCallUi?,
    micDenied: Boolean,
    onEvent: (VoipCallEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(OpenCallerSpacing.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Header(state)
            if (micDenied) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.voip_microphone_needed), textAlign = TextAlign.Center)
                    Button(onClick = { onEvent(VoipCallEvent.AllowMicrophone) }) { Text(stringResource(R.string.voip_allow_microphone)) }
                }
            }
            state?.takeIf { it.isLive }?.let { Buttons(it, onEvent) }
        }
    }
}

@Composable
private fun Header(state: AppCallUi?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
        state?.clipPath?.takeIf { state.phase == AppCallPhase.RINGING_IN || state.phase == AppCallPhase.ANSWERING }?.let {
            MutedClip(
                file = File(it),
                contentDescription = stringResource(R.string.voip_video_caller_id),
                modifier = Modifier.fillMaxWidth().height(OpenCallerSpacing.videoCallerId),
            )
        }
        Text(state?.name ?: state?.number.orEmpty(), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        if (state?.name != null) Text(state.number.orEmpty(), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(statusOf(state)), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.voip_via_app), style = MaterialTheme.typography.bodySmall)
    }
}

@StringRes
internal fun statusOf(state: AppCallUi?): Int =
    when (state?.phase) {
        null -> R.string.voip_ended
        AppCallPhase.CONNECTING -> R.string.voip_connecting
        AppCallPhase.RINGING_OUT -> R.string.voip_ringing_out
        AppCallPhase.RINGING_IN -> R.string.voip_ringing_in
        AppCallPhase.ANSWERING -> R.string.voip_answering
        AppCallPhase.ACTIVE -> R.string.voip_active
        AppCallPhase.ENDED -> endText(state.end)
    }

@StringRes
private fun endText(end: AppCallEnd?): Int =
    when (end) {
        AppCallEnd.DECLINED -> R.string.voip_declined
        AppCallEnd.MISSED -> R.string.voip_missed
        AppCallEnd.UNREACHABLE -> R.string.voip_unreachable
        AppCallEnd.FAILED -> R.string.voip_failed
        AppCallEnd.BUSY -> R.string.voip_busy
        AppCallEnd.HUNG_UP, null -> R.string.voip_ended
    }

@Composable
private fun Buttons(
    state: AppCallUi,
    onEvent: (VoipCallEvent) -> Unit,
) {
    val hangUp = if (state.phase == AppCallPhase.RINGING_IN) R.string.voip_decline else R.string.voip_hang_up
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.l)) {
        if (state.phase != AppCallPhase.RINGING_IN) {
            Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.l)) {
                CallControlButton(OpenCallerIcons.MicOff, stringResource(R.string.voip_mute), {
                    onEvent(VoipCallEvent.Mute(!state.muted))
                }, checked = state.muted)
                CallControlButton(OpenCallerIcons.Speaker, stringResource(R.string.voip_speaker), {
                    onEvent(VoipCallEvent.Speaker(!state.speaker))
                }, checked = state.speaker)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            CallRoundAction(
                icon = OpenCallerIcons.CallEnd,
                label = stringResource(hangUp),
                container = OpenCallerTheme.semanticColors.danger,
                content = OpenCallerTheme.semanticColors.onDanger,
                onClick = { onEvent(VoipCallEvent.HangUp) },
            )
            if (state.phase == AppCallPhase.RINGING_IN) {
                CallRoundAction(
                    icon = OpenCallerIcons.Calls,
                    label = stringResource(R.string.voip_answer),
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    onClick = { onEvent(VoipCallEvent.Answer) },
                )
            }
        }
    }
}

@OpenCallerPreviews
@Composable
private fun RingingPreview() {
    OpenCallerTheme {
        VoipCallScreen(
            AppCallUi(AppCallPhase.RINGING_IN, incoming = true, number = "+12015550123", name = "Asha"),
            micDenied = false,
            onEvent = {},
        )
    }
}

@OpenCallerPreviews
@Composable
private fun ActivePreview() {
    OpenCallerTheme {
        VoipCallScreen(
            AppCallUi(AppCallPhase.ACTIVE, incoming = false, number = "+12015550123", muted = true),
            micDenied = false,
            onEvent = {},
        )
    }
}
