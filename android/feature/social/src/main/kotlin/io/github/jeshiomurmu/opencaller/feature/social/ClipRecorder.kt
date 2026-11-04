package io.github.jeshiomurmu.opencaller.feature.social

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import timber.log.Timber
import java.io.File

/**
 * Records the user's video caller ID with CameraX: the front camera at SD quality (or the nearest lower one), no
 * audio (the recording never asks for the microphone), stopped by CameraX after [MAX_MILLIS]. The server cuts and
 * shrinks it again; this keeps the upload small.
 */
internal class ClipRecorder(
    private val context: Context,
) {
    private val capture =
        VideoCapture.withOutput(
            Recorder
                .Builder()
                .setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                .build(),
        )
    private var recording: Recording? = null

    /** Shows the front camera in [view], bound to [owner]'s lifecycle. */
    fun bind(
        owner: LifecycleOwner,
        view: PreviewView,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, capture)
        }, context.mainExecutor)
    }

    /** Starts recording into [file]; [onDone] gets the file, or `null` when nothing usable was recorded. */
    fun start(
        file: File,
        onDone: (File?) -> Unit,
    ) {
        // Not chained: the builder's setters return its generic self type.
        val builder = FileOutputOptions.Builder(file)
        builder.setDurationLimitMillis(MAX_MILLIS)
        builder.setFileSizeLimit(MAX_BYTES)
        val options = builder.build()
        recording =
            capture.output.prepareRecording(context, options).start(context.mainExecutor) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                    val usable = !event.hasError() || event.error in STOPPED_BY_LIMIT
                    if (!usable) Timber.w("Clip not recorded (error %d)", event.error)
                    onDone(file.takeIf { usable && it.length() > 0 })
                }
            }
    }

    /** Stops a recording early. */
    fun stop() {
        recording?.stop()
        recording = null
    }

    /** Limits. */
    companion object {
        /** Longest clip. */
        const val MAX_MILLIS = 10_000L
        private const val MAX_BYTES = 20L * 1024 * 1024
        private val STOPPED_BY_LIMIT =
            setOf(VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED, VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED)
    }
}
