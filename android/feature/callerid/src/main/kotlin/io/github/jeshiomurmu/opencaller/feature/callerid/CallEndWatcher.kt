package io.github.jeshiomurmu.opencaller.feature.callerid

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject

/** Where a call is, as far as caller ID cares. */
enum class CallPhase {
    /** No call. */
    IDLE,

    /** Ringing. */
    RINGING,

    /** Answered (or dialling). */
    ACTIVE,
}

/**
 * Turns a stream of call phases into one "the call ended" signal: the end is an [CallPhase.IDLE] after the call was
 * seen ringing or active. An IDLE first report is ignored, because Android screens a call before it starts ringing.
 */
class CallEndDetector {
    private var sawCall = false
    private var ended = false

    /** Feeds [phase]; returns true exactly once, when the call has ended. */
    fun onPhase(phase: CallPhase): Boolean {
        if (ended) return false
        if (phase != CallPhase.IDLE) sawCall = true
        ended = sawCall && phase == CallPhase.IDLE
        return ended
    }
}

/** A running watch; [stop] is safe to call more than once. */
fun interface CallWatch {
    /** Stops watching. */
    fun stop()
}

/** Tells caller ID when the current call ends. */
interface CallEndWatcher {
    /** True when the end of every call can be observed (call state readable); false when only rung calls can. */
    fun canObserveEveryCall(): Boolean

    /** Calls [onEnded] on the main thread once the call ends. Call from the main thread. */
    fun watch(onEnded: () -> Unit): CallWatch
}

/**
 * [CallEndWatcher] on the device, so the card closes and the after-call summary appears (docs/architecture.md §4.3):
 * `TelephonyCallback` on Android 12+ when OpenCaller may read the call state, `PhoneStateListener` before Android 12
 * (no permission needed there), and otherwise the audio mode, which goes back to normal when a call that rang or was
 * answered ends (a silenced call never changes it, so the caller falls back to a timeout).
 */
class DeviceCallEndWatcher
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : CallEndWatcher {
        override fun canObserveEveryCall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canReadCallState()

        override fun watch(onEnded: () -> Unit): CallWatch {
            val detector = CallEndDetector()
            val telephony = context.getSystemService(TelephonyManager::class.java)
            val phases: (CallPhase) -> Boolean = detector::onPhase
            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canReadCallState() && telephony != null -> {
                    watchCallState(telephony, phases, onEnded) ?: watchAudioMode(phases, onEnded)
                }

                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    watchAudioMode(phases, onEnded)
                }

                telephony != null -> {
                    watchLegacyCallState(telephony, phases, onEnded)
                }

                else -> {
                    CallWatch {}
                }
            }
        }

        private fun canReadCallState(): Boolean =
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

        // The caller checked READ_PHONE_STATE; a revoke in between surfaces as SecurityException and falls back.
        @SuppressLint("MissingPermission")
        @RequiresApi(Build.VERSION_CODES.S)
        private fun watchCallState(
            telephony: TelephonyManager,
            phases: (CallPhase) -> Boolean,
            onEnded: () -> Unit,
        ): CallWatch? {
            val callback =
                object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        if (phases(state.toPhase())) {
                            telephony.unregisterTelephonyCallback(this)
                            onEnded()
                        }
                    }
                }
            return try {
                telephony.registerTelephonyCallback(context.mainExecutor, callback)
                CallWatch { telephony.unregisterTelephonyCallback(callback) }
            } catch (e: SecurityException) {
                Timber.w(e, "Call state not readable; watching the audio mode instead")
                null
            }
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun watchAudioMode(
            phases: (CallPhase) -> Boolean,
            onEnded: () -> Unit,
        ): CallWatch {
            val audio = context.getSystemService(AudioManager::class.java) ?: return CallWatch {}
            val listener =
                object : AudioManager.OnModeChangedListener {
                    override fun onModeChanged(mode: Int) {
                        if (phases(mode.audioModeToPhase())) {
                            audio.removeOnModeChangedListener(this)
                            onEnded()
                        }
                    }
                }
            phases(audio.mode.audioModeToPhase())
            audio.addOnModeChangedListener(context.mainExecutor, listener)
            return CallWatch { audio.removeOnModeChangedListener(listener) }
        }

        // Android 10 and 11: the call state needs no permission. PhoneStateListener is deprecated from Android 12,
        // which takes the TelephonyCallback path above.
        @Suppress("DEPRECATION")
        private fun watchLegacyCallState(
            telephony: TelephonyManager,
            phases: (CallPhase) -> Boolean,
            onEnded: () -> Unit,
        ): CallWatch {
            val listener =
                object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(
                        state: Int,
                        phoneNumber: String?,
                    ) {
                        if (phases(state.toPhase())) {
                            telephony.listen(this, PhoneStateListener.LISTEN_NONE)
                            onEnded()
                        }
                    }
                }
            telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            return CallWatch { telephony.listen(listener, PhoneStateListener.LISTEN_NONE) }
        }
    }

/** A `TelephonyManager.CALL_STATE_*` value as a [CallPhase]. */
internal fun Int.toPhase(): CallPhase =
    when (this) {
        TelephonyManager.CALL_STATE_RINGING -> CallPhase.RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> CallPhase.ACTIVE
        else -> CallPhase.IDLE
    }

/**
 * An `AudioManager.MODE_*` value as a [CallPhase]: ringtone is ringing; every call and communication mode (in call,
 * in communication, call screening, redirects) is a positive value above it and counts as active.
 */
internal fun Int.audioModeToPhase(): CallPhase =
    when {
        this == AudioManager.MODE_RINGTONE -> CallPhase.RINGING
        this > AudioManager.MODE_RINGTONE -> CallPhase.ACTIVE
        else -> CallPhase.IDLE
    }
