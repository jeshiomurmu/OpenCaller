package io.github.jeshiomurmu.opencaller.core.telephony.otp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.telephony.incall.ScreenedCalls
import io.github.jeshiomurmu.opencaller.core.telephony.providers.DefaultPhoneAppRole
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feeds [CallActivityTracker] from the phone's call state when OpenCaller is not the default phone app
 * (screening-only mode): `TelephonyCallback` on Android 12+ (needs `READ_PHONE_STATE`), `PhoneStateListener` before
 * (no permission needed there). The call state has no number, so the other side is the call OpenCaller's screening
 * service saw just before; a call it didn't see (placed while screening is off) counts as a stranger.
 */
@Singleton
class PhoneStateFeed
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val tracker: CallActivityTracker,
        private val screened: ScreenedCalls,
        private val dialerRole: DefaultPhoneAppRole,
    ) {
        private var started = false
        private var callNumber = 0
        private var inCall = false

        /** Starts listening, once, when the call state can be read. Call on the main thread. */
        fun start() {
            if (started) return
            val telephony = context.getSystemService(TelephonyManager::class.java)
            started =
                when {
                    telephony == null -> false
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> canReadCallState() && listen(telephony)
                    else -> listenLegacy(telephony)
                }
        }

        /** One `TelephonyManager.CALL_STATE_*` change. */
        internal fun onState(state: Int) {
            // As the default phone app, the in-call service reports every call with its number.
            if (dialerRole.isHeld()) return
            val id = "phone:$callNumber"
            when (state) {
                TelephonyManager.CALL_STATE_RINGING, TelephonyManager.CALL_STATE_OFFHOOK -> {
                    if (!inCall) {
                        inCall = true
                        val result = screened.newest(SCREENED_WITHIN_MILLIS)
                        val party = if (result?.caller?.nameSource == NameSource.CONTACT) CallParty.CONTACT else CallParty.STRANGER
                        val incoming = result?.direction == Direction.INCOMING || state == TelephonyManager.CALL_STATE_RINGING
                        tracker.started(id, result?.caller?.number, party, incoming)
                    }
                    if (state == TelephonyManager.CALL_STATE_OFFHOOK) tracker.answered(id)
                }

                else -> {
                    if (inCall) tracker.ended(id)
                    inCall = false
                    callNumber++
                }
            }
        }

        private fun canReadCallState(): Boolean =
            context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

        // The caller checked READ_PHONE_STATE; a revoke in between surfaces as SecurityException.
        @SuppressLint("MissingPermission")
        @RequiresApi(Build.VERSION_CODES.S)
        private fun listen(telephony: TelephonyManager): Boolean {
            val callback =
                object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) = onState(state)
                }
            return try {
                telephony.registerTelephonyCallback(context.mainExecutor, callback)
                true
            } catch (e: SecurityException) {
                Timber.w(e, "Call state not readable; the OTP guard sees only calls of the default phone app")
                false
            }
        }

        // Android 10 and 11: the call state needs no permission. PhoneStateListener is deprecated from Android 12,
        // which takes the TelephonyCallback path above.
        @Suppress("DEPRECATION")
        private fun listenLegacy(telephony: TelephonyManager): Boolean {
            val listener =
                object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(
                        state: Int,
                        phoneNumber: String?,
                    ) = onState(state)
                }
            telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            return true
        }

        private companion object {
            // Android screens a call right before it rings or dials.
            const val SCREENED_WITHIN_MILLIS = 30_000L
        }
    }
