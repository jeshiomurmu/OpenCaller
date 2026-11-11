package io.github.jeshiomurmu.opencaller.feature.recording

import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Plays saved recordings (Opus in OGG, which Android plays from Android 10, the app's minimum). */
interface RecordingPlayer {
    /** The id of the recording playing now, or `null`. */
    val playing: StateFlow<String?>

    /** Plays the file at [path] as recording [id], stopping any other. False if the file can't be played. */
    fun play(
        id: String,
        path: String,
    ): Boolean

    /** Stops playback. */
    fun stop()
}

/** [RecordingPlayer] with [MediaPlayer]. */
@Singleton
class MediaRecordingPlayer
    @Inject
    constructor() : RecordingPlayer {
        private val mutablePlaying = MutableStateFlow<String?>(null)
        private var player: MediaPlayer? = null

        override val playing: StateFlow<String?> = mutablePlaying.asStateFlow()

        override fun play(
            id: String,
            path: String,
        ): Boolean {
            stop()
            val next = MediaPlayer()
            return try {
                next.setDataSource(path)
                next.setOnCompletionListener { stop() }
                next.prepare()
                next.start()
                player = next
                mutablePlaying.value = id
                true
            } catch (e: IOException) {
                Timber.w(e, "Recording can't be played")
                next.release()
                false
            } catch (e: IllegalStateException) {
                Timber.w(e, "Recording can't be played")
                next.release()
                false
            }
        }

        override fun stop() {
            player?.release()
            player = null
            mutablePlaying.value = null
        }
    }
