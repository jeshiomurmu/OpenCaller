package io.github.jeshiomurmu.opencaller.feature.incall.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.telephony.incall.AudioRoute
import io.github.jeshiomurmu.opencaller.core.telephony.incall.AudioRouteType
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallAudio
import io.github.jeshiomurmu.opencaller.feature.incall.R

/** Longest quick reply the user can type (one SMS). */
private const val REPLY_MAX_LENGTH = 160

/**
 * Decline with a message: the system's quick replies (they can arrive a moment after the call does) or the user's own
 * text. Telecom sends it through the default SMS app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickReplySheet(
    replies: List<String>,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        QuickReplies(replies = replies, onSend = onSend, modifier = Modifier.navigationBarsPadding())
    }
}

@Composable
private fun QuickReplies(
    replies: List<String>,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var custom by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.incall_reply_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        replies.forEach { reply -> SheetRow(text = reply, onClick = { onSend(reply) }) }
        OutlinedTextField(
            value = custom,
            onValueChange = { custom = it.take(REPLY_MAX_LENGTH) },
            label = { Text(stringResource(R.string.incall_reply_custom_label)) },
            placeholder = { Text(stringResource(R.string.incall_reply_custom)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onSend(custom) },
            enabled = custom.isNotBlank(),
            modifier = Modifier.align(Alignment.End).padding(bottom = OpenCallerSpacing.m),
        ) {
            Text(stringResource(R.string.incall_reply_send))
        }
    }
}

/** Where call audio plays: every route Telecom offers, the current one marked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioRouteSheet(
    audio: CallAudio,
    onRoute: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        AudioRoutes(audio = audio, onRoute = onRoute, modifier = Modifier.navigationBarsPadding())
    }
}

@Composable
private fun AudioRoutes(
    audio: CallAudio,
    onRoute: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(bottom = OpenCallerSpacing.m).selectableGroup()) {
        Text(
            text = stringResource(R.string.incall_audio_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = OpenCallerSpacing.screenPadding).semantics { heading() },
        )
        audio.available.forEach { route ->
            val selected = route.id == audio.current?.id
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = OpenCallerSpacing.minTouchTarget)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = { onRoute(route.id) })
                        .padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
            ) {
                Icon(painter = painterResource(routeIcon(route.type)), contentDescription = null)
                Text(text = routeLabel(route), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (selected) Icon(painter = painterResource(OpenCallerIcons.Granted), contentDescription = null)
            }
        }
    }
}

/** A tappable line in a sheet. */
@Composable
internal fun SheetRow(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = OpenCallerSpacing.minTouchTarget)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(vertical = OpenCallerSpacing.s),
    )
}

/** The icon for an audio route. */
@DrawableRes
internal fun routeIcon(type: AudioRouteType?): Int =
    when (type) {
        AudioRouteType.SPEAKER -> OpenCallerIcons.Speaker
        AudioRouteType.BLUETOOTH -> OpenCallerIcons.Bluetooth
        AudioRouteType.WIRED_HEADSET -> OpenCallerIcons.Headset
        AudioRouteType.EARPIECE, AudioRouteType.STREAMING, AudioRouteType.UNKNOWN, null -> OpenCallerIcons.Earpiece
    }

/** A route's name: the device's own name when Android gives it (Bluetooth on Android 14+), else its kind. */
@Composable
internal fun routeLabel(route: AudioRoute): String =
    route.name ?: stringResource(
        when (route.type) {
            AudioRouteType.EARPIECE -> R.string.incall_route_earpiece
            AudioRouteType.SPEAKER -> R.string.incall_route_speaker
            AudioRouteType.BLUETOOTH -> R.string.incall_route_bluetooth
            AudioRouteType.WIRED_HEADSET -> R.string.incall_route_wired
            AudioRouteType.STREAMING -> R.string.incall_route_streaming
            AudioRouteType.UNKNOWN -> R.string.incall_route_unknown
        },
    )

@OpenCallerPreviews
@Composable
private fun QuickRepliesPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            QuickReplies(replies = listOf("Can't talk now. What's up?", "I'll call you right back."), onSend = {})
        }
    }
}

@OpenCallerPreviews
@Composable
private fun AudioRoutesPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            AudioRoutes(audio = InCallSamples.headsetAudio, onRoute = {})
        }
    }
}
