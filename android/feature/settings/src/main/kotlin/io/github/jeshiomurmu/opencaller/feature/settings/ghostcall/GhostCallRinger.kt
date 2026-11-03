package io.github.jeshiomurmu.opencaller.feature.settings.ghostcall

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import timber.log.Timber

/**
 * The fake call's ring: the phone's own ringtone, looping, when the ringer is on; the vibration pattern when it's on
 * vibrate; nothing when it's silent or Do Not Disturb filters interruptions.
 */
class GhostCallRinger(
    private val context: Context,
) {
    private var ringtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    /** Starts ringing (or vibrating). */
    fun start() {
        stop()
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val filterAll =
            context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter ==
                NotificationManager.INTERRUPTION_FILTER_ALL
        if (!filterAll) return
        when (audio.ringerMode) {
            AudioManager.RINGER_MODE_NORMAL -> {
                ring()
                vibrate()
            }

            AudioManager.RINGER_MODE_VIBRATE -> {
                vibrate()
            }

            else -> {} // silent: the screen alone
        }
    }

    /** Stops ringing and vibrating. */
    fun stop() {
        ringtone?.stop()
        ringtone = null
        vibrator?.cancel()
        vibrator = null
    }

    private fun ring() {
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE) ?: return
        val tone = RingtoneManager.getRingtone(context, uri) ?: return
        tone.audioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        tone.isLooping = true
        try {
            tone.play()
            ringtone = tone
        } catch (e: IllegalStateException) {
            Timber.w(e, "Ghost Call ringtone could not play")
        }
    }

    private fun vibrate() {
        val device = context.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() } ?: return
        device.vibrate(VibrationEffect.createWaveform(PATTERN, 0))
        vibrator = device
    }

    private companion object {
        // Off, on, off, on (ms), repeated from the start: a call-like double buzz.
        val PATTERN = longArrayOf(0, 800, 400, 800, 1_600)
    }
}
