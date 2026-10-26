package io.github.jeshiomurmu.opencaller.feature.incall

import android.content.Context
import android.os.PowerManager

/**
 * Turns the screen off while the phone is at the ear during a call through the earpiece, so a cheek can't press Hang
 * up (`PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK`). Does nothing on devices without a proximity sensor.
 */
internal class ProximityScreenLock(
    context: Context,
) {
    private val lock: PowerManager.WakeLock? =
        context
            .getSystemService(PowerManager::class.java)
            ?.takeIf { it.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) }
            ?.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, TAG)
    private var wanted = false
    private var started = false

    /** Whether the call plays through the earpiece. */
    fun want(earpiece: Boolean) {
        wanted = earpiece
        apply()
    }

    /** The in-call screen is visible ([visible] true) or not (the lock is then let go). */
    fun onVisible(visible: Boolean) {
        started = visible
        apply()
    }

    private fun apply() {
        val lock = lock ?: return
        val hold = wanted && started
        when {
            hold && !lock.isHeld -> lock.acquire(MAX_CALL_MILLIS)
            !hold && lock.isHeld -> lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        }
    }

    private companion object {
        const val TAG = "OpenCaller:proximity"

        // A safety limit only; the lock is let go as soon as the call leaves the earpiece or the screen closes.
        const val MAX_CALL_MILLIS = 4 * 60 * 60 * 1000L
    }
}
