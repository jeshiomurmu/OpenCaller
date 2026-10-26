package io.github.jeshiomurmu.opencaller.feature.incall

import android.content.ActivityNotFoundException
import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.annotation.RequiresApi
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.telephony.incall.AfterCallPresenter
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.EndedCall
import io.github.jeshiomurmu.opencaller.feature.incall.calls.AndroidTelecomCall
import io.github.jeshiomurmu.opencaller.feature.incall.calls.CallRegistry
import io.github.jeshiomurmu.opencaller.feature.incall.calls.CallerCache
import io.github.jeshiomurmu.opencaller.feature.incall.calls.InCallAudio
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.IdentityHashMap
import javax.inject.Inject

/**
 * OpenCaller's in-call service: Telecom binds it while OpenCaller is the default phone app (`ROLE_DIALER`) and hands
 * it every call (developer.android.com, `InCallService`). It tracks the calls ([CallRegistry]), keeps the incoming and
 * ongoing notifications current, opens the in-call screen for calls the user places, and passes ended calls to the
 * after-call card. Ringing is left to Telecom. Screening still happens first, in `OpenCallerScreeningService`.
 */
@Suppress("TooManyFunctions") // InCallService callbacks.
@AndroidEntryPoint
class OpenCallerInCallService : InCallService() {
    @Inject
    internal lateinit var registry: CallRegistry

    @Inject
    internal lateinit var callers: CallerCache

    @Inject
    internal lateinit var notifications: CallNotifications

    @Inject
    lateinit var visibility: InCallVisibility

    @Inject
    lateinit var afterCall: AfterCallPresenter

    @Inject
    lateinit var dispatchers: DispatcherProvider

    private lateinit var audio: InCallAudio
    private lateinit var scope: CoroutineScope
    private val tracked = IdentityHashMap<Call, AndroidTelecomCall>()

    override fun onCreate() {
        super.onCreate()
        audio = InCallAudio(this, registry)
        registry.attach(audio)
        registry.updateCanAddCall(canAddCall())
        scope =
            CoroutineScope(SupervisorJob() + dispatchers.main + CoroutineExceptionHandler { _, e -> Timber.w(e, "In-call service failed") })
        scope.launch {
            registry.state.combine(callers.callers) { state, known -> state to known }.collect { (state, known) ->
                callers.sync(state)
                notifications.render(state, known)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        notifications.cancelAll()
        registry.attach(null)
        tracked.clear()
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        val wrapped = AndroidTelecomCall(call, ::simLabel)
        tracked[call] = wrapped
        val id = registry.add(wrapped)
        // Ringing calls show through the incoming notification (full screen when the phone isn't in use). A call the
        // user places opens the in-call screen at once; if Android doesn't let it start, the ongoing notification
        // leads there.
        if (registry.state.value
                .call(id)
                ?.status != CallStatus.RINGING
        ) {
            showInCallScreen(showDialpad = false)
        }
    }

    override fun onCallRemoved(call: Call) {
        val wrapped = tracked.remove(call) ?: return
        // The caller is read before the call leaves the registry (the cache forgets calls that are gone).
        val caller = registry.idOf(wrapped.key)?.let(callers::callerOf)
        val last = registry.remove(wrapped)
        val ended = if (caller != null && last != null) EndedCall(caller, last.direction, answered = last.connectedAtMillis > 0) else null
        if (ended?.wantsAfterCall == true) afterCall.present(ended, visibility.isVisible)
    }

    override fun onCanAddCallChanged(canAddCall: Boolean) {
        registry.updateCanAddCall(canAddCall)
    }

    override fun onBringToForeground(showDialpad: Boolean) {
        showInCallScreen(showDialpad)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        audio.onCurrentEndpoint(callEndpoint)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onAvailableCallEndpointsChanged(availableEndpoints: List<CallEndpoint>) {
        audio.onAvailableEndpoints(availableEndpoints)
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        audio.onMuted(isMuted)
    }

    // Before Android 14 the audio state arrives here; from Android 14 the endpoint callbacks above replace it.
    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) audio.onLegacyAudioState(audioState)
    }

    private fun showInCallScreen(showDialpad: Boolean) {
        try {
            startActivity(InCallIntents.show(this, showDialpad))
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "In-call screen could not be opened")
        }
    }

    // A SIM's name for the SIM chooser; Android 12+ requires READ_PHONE_NUMBERS to read it.
    private fun simLabel(handle: PhoneAccountHandle): String? =
        try {
            getSystemService(TelecomManager::class.java)
                ?.getPhoneAccount(handle)
                ?.label
                ?.toString()
                ?.takeIf(String::isNotBlank)
        } catch (e: SecurityException) {
            Timber.w(e, "SIM name not readable without the phone numbers permission")
            null
        }
}
