package io.github.jeshiomurmu.opencaller.feature.social

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.FlashKind
import io.github.jeshiomurmu.opencaller.core.model.FlashMessage
import io.github.jeshiomurmu.opencaller.core.model.MyVideoCallerId
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.VideoJob

/** Settings → App calls, Flash and video caller ID, wired to its view model; [onRecord] opens the recorder. */
@Composable
fun VoipSettingsRoute(
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
    recordedClip: String? = null,
    onClipTaken: () -> Unit = {},
    viewModel: VoipSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(recordedClip) {
        recordedClip?.let {
            viewModel.onEvent(VoipSettingsEvent.Upload(it))
            onClipTaken()
        }
    }
    VoipSettingsScreen(state = state, onEvent = viewModel::onEvent, onRecord = onRecord, modifier = modifier)
}

/** The settings screen. */
@Composable
fun VoipSettingsScreen(
    state: VoipSettingsUiState,
    onEvent: (VoipSettingsEvent) -> Unit,
    onRecord: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
    ) {
        Text(stringResource(R.string.voip_settings_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        if (!state.signedIn) {
            Text(stringResource(R.string.voip_settings_signed_out))
            return@Column
        }
        state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        SettingSwitch(
            title = stringResource(R.string.voip_reachable),
            summary = stringResource(R.string.voip_reachable_summary),
            checked = state.reachable ?: true,
            enabled = state.reachable != null,
            onChange = { onEvent(VoipSettingsEvent.SetReachable(it)) },
        )
        SettingSwitch(
            title = stringResource(R.string.voip_clips_mobile_data),
            summary = stringResource(R.string.voip_clips_mobile_data_summary),
            checked = state.clipsOnMobileData,
            onChange = { onEvent(VoipSettingsEvent.SetClipsOnMobileData(it)) },
        )
        HorizontalDivider()
        MyVideoSection(state, onEvent, onRecord)
        HorizontalDivider()
        RecentFlash(state.recent)
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    summary: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = OpenCallerSpacing.m)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun MyVideoSection(
    state: VoipSettingsUiState,
    onEvent: (VoipSettingsEvent) -> Unit,
    onRecord: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
        Text(stringResource(R.string.video_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.video_explanation), style = MaterialTheme.typography.bodySmall)
        state.video?.let { Text(videoStatus(it)) }
        if (state.busy) {
            Text(stringResource(R.string.video_uploading))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
            Button(onClick = onRecord, enabled = !state.busy) { Text(stringResource(R.string.video_record)) }
            if (state.video?.videoId != null) {
                OutlinedButton(onClick = { onEvent(VoipSettingsEvent.DeleteVideo) }, enabled = !state.busy) {
                    Text(stringResource(R.string.video_delete))
                }
            }
        }
    }
}

@Composable
private fun videoStatus(video: MyVideoCallerId): String =
    when {
        video.job == VideoJob.FAILED -> stringResource(R.string.video_failed, video.error.orEmpty())
        video.job == VideoJob.QUEUED || video.job == VideoJob.PROCESSING -> stringResource(R.string.video_processing)
        video.hidden -> stringResource(R.string.video_hidden)
        video.videoId != null -> stringResource(R.string.video_published)
        else -> stringResource(R.string.video_none)
    }

@Composable
private fun RecentFlash(recent: List<FlashMessage>) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
        Text(stringResource(R.string.flash_recent_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (recent.isEmpty()) Text(stringResource(R.string.flash_recent_empty), style = MaterialTheme.typography.bodySmall)
        recent.forEach { message ->
            val who = message.name ?: message.number.e164
            val text = stringResource(message.kind.label())
            Text(stringResource(if (message.outgoing) R.string.flash_sent_line else R.string.flash_received_line, who, text))
        }
    }
}

@OpenCallerPreviews
@Composable
private fun VoipSettingsPreview() {
    OpenCallerTheme {
        VoipSettingsScreen(
            state =
                VoipSettingsUiState(
                    signedIn = true,
                    reachable = true,
                    video = MyVideoCallerId("3f2d1c4b-5a69-4e7f-8a9b-0c1d2e3f4a5b", VideoJob.NONE, null, false),
                    recent = listOf(FlashMessage("f1", FlashKind.RUNNING_LATE, PhoneNumber("+12015550123"), "Asha", 0L, outgoing = false)),
                ),
            onEvent = {},
            onRecord = {},
        )
    }
}
