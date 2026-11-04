package io.github.jeshiomurmu.opencaller.core.ui

import android.media.AudioManager
import android.net.Uri
import android.widget.VideoView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import timber.log.Timber
import java.io.File

/**
 * Plays a video caller ID [file] muted and in a loop (the in-call screens). It never takes audio focus, so the
 * ringtone and the call keep the speaker; a clip that can't be played simply shows nothing.
 */
@Composable
fun MutedClip(
    file: File,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            VideoView(context).apply {
                setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
                setOnPreparedListener { player ->
                    player.setVolume(0f, 0f)
                    player.isLooping = true
                    start()
                }
                setOnErrorListener { _, what, extra ->
                    Timber.i("Video caller ID not played (%d, %d)", what, extra)
                    true
                }
                setVideoURI(Uri.fromFile(file))
            }
        },
        onRelease = VideoView::stopPlayback,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
    )
}
