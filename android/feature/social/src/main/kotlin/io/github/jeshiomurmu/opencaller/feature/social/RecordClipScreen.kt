package io.github.jeshiomurmu.opencaller.feature.social

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.ui.MutedClip
import kotlinx.coroutines.delay
import java.io.File

/** Where the recorder is. */
private enum class RecordStage { PREVIEW, RECORDING, DONE, FAILED }

/**
 * Records the user's video caller ID: camera permission (asked here only), a front-camera preview, Record for at
 * most 10 s without sound, then a muted replay with "Use this clip" ([onRecorded] gets the file) or "Record again".
 */
@Composable
fun RecordClipRoute(
    onRecorded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Column(
        modifier = modifier.fillMaxSize().padding(OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
    ) {
        Text(stringResource(R.string.record_title), style = MaterialTheme.typography.titleLarge)
        if (granted) {
            Recorder(onRecorded = onRecorded, modifier = Modifier.weight(1f))
        } else {
            Text(stringResource(R.string.record_camera_needed))
            Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.record_allow_camera)) }
        }
    }
}

@Composable
private fun Recorder(
    onRecorded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val recorder = remember { ClipRecorder(context) }
    val file = remember { File(context.cacheDir, "my-video-caller-id.mp4") }
    var stage by remember { mutableStateOf(RecordStage.PREVIEW) }
    val seconds = recordingSeconds(stage)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
        if (stage == RecordStage.DONE) {
            MutedClip(file = file, contentDescription = stringResource(R.string.video_title), modifier = Modifier.weight(1f).fillMaxWidth())
        } else {
            AndroidView(
                factory = { PreviewView(it).also { view -> recorder.bind(owner, view) } },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        when (stage) {
            RecordStage.RECORDING -> Text(stringResource(R.string.record_recording, seconds))
            RecordStage.FAILED -> Text(stringResource(R.string.record_failed), color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
        RecordButtons(
            stage = stage,
            onStart = {
                file.delete()
                stage = RecordStage.RECORDING
                recorder.start(file) { stage = if (it != null) RecordStage.DONE else RecordStage.FAILED }
            },
            onStop = recorder::stop,
            onUse = { onRecorded(file.absolutePath) },
            onAgain = { stage = RecordStage.PREVIEW },
        )
    }
}

// Seconds since the recording started, counted while it records.
@Composable
private fun recordingSeconds(stage: RecordStage): Int {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(stage) {
        seconds = 0
        while (stage == RecordStage.RECORDING) {
            delay(TICK_MILLIS)
            seconds++
        }
    }
    return seconds
}

@Composable
private fun RecordButtons(
    stage: RecordStage,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onUse: () -> Unit,
    onAgain: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
        when (stage) {
            RecordStage.PREVIEW, RecordStage.FAILED -> {
                Button(onClick = onStart) { Text(stringResource(R.string.record_start)) }
            }

            RecordStage.RECORDING -> {
                Button(onClick = onStop) { Text(stringResource(R.string.record_stop)) }
            }

            RecordStage.DONE -> {
                Button(onClick = onUse) { Text(stringResource(R.string.record_upload)) }
                OutlinedButton(onClick = onAgain) { Text(stringResource(R.string.record_again)) }
            }
        }
    }
}

private const val TICK_MILLIS = 1_000L
