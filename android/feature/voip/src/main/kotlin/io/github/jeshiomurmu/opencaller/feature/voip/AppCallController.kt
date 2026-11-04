package io.github.jeshiomurmu.opencaller.feature.voip

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.voip.AppCallAction
import io.github.jeshiomurmu.opencaller.core.data.voip.AppCallHandler
import io.github.jeshiomurmu.opencaller.core.data.voip.AppCallRepository
import io.github.jeshiomurmu.opencaller.core.data.voip.VideoClips
import io.github.jeshiomurmu.opencaller.core.model.AppCall
import io.github.jeshiomurmu.opencaller.core.model.AppCallState
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App calls on this phone: one at a time. Pushes arrive through [AppCallHandler]; the call screen and the
 * notifications read [state] and send the user's actions here. [VoipCallService] keeps the process in the
 * foreground while a call rings or runs.
 */
@Singleton
class AppCallController
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        calls: AppCallRepository,
        private val clips: VideoClips,
        private val clock: Clock,
        dispatchers: DispatcherProvider,
    ) : AppCallHandler {
        private val scope =
            CoroutineScope(SupervisorJob() + dispatchers.main + CoroutineExceptionHandler { _, e -> Timber.w(e, "App call failed") })
        private val deps = SessionDeps(context, calls, clock)
        private val mutableState = MutableStateFlow<AppCallUi?>(null)
        private var session: AppCallSession? = null

        /** The current (or just ended) call, `null` when there was none. */
        val state: StateFlow<AppCallUi?> = mutableState.asStateFlow()

        override fun onInvite(call: AppCall) {
            scope.launch {
                val number = call.number ?: return@launch
                when {
                    call.state != AppCallState.RINGING || call.ringUntilMillis <= clock.nowMillis() -> Unit
                    session?.state?.isLive == true -> deps.calls.act(call.inviteId, AppCallAction.DECLINE)
                    else -> ring(call, number)
                }
            }
        }

        override fun onUpdate(call: AppCall) {
            scope.launch { session?.takeIf { it.state.inviteId == call.inviteId }?.onServer(call.state) }
        }

        /** Calls [e164] over the app (from the call screen, after the microphone was allowed). */
        fun call(e164: String) {
            if (!PhoneNumber.isE164(e164) || session?.state?.isLive == true) return
            val created = newSession(AppCallUi(phase = AppCallPhase.CONNECTING, incoming = false, number = e164))
            startService()
            created.callOut(PhoneNumber(e164))
        }

        /** Answers the ringing call (from the call screen, after the microphone was allowed). */
        fun answer() {
            // Again, now with the microphone type: the call screen is in the foreground.
            startService()
            session?.answer()
        }

        /** Hangs up, declines or cancels. */
        fun hangUp() = session?.hangUp()

        /** Mutes or unmutes. */
        fun setMuted(muted: Boolean) = session?.setMuted(muted)

        /** Speaker on or off. */
        fun setSpeaker(on: Boolean) = session?.setSpeaker(on)

        private suspend fun ring(
            call: AppCall,
            number: PhoneNumber,
        ) {
            val ui =
                AppCallUi(
                    phase = AppCallPhase.RINGING_IN,
                    incoming = true,
                    number = number.e164,
                    name = call.name,
                    inviteId = call.inviteId,
                )
            val created = newSession(ui)
            startService()
            created.ringIn(call.ringUntilMillis)
            // The caller's clip, on Wi-Fi unless the user allows mobile data (it may arrive while it rings).
            call.videoId?.let { id ->
                clips.download(listOf(id))
                clips.clip(id)?.let { file -> mutableState.value = mutableState.value?.copy(clipPath = file.absolutePath) }
            }
        }

        private fun newSession(ui: AppCallUi): AppCallSession {
            mutableState.value = ui
            return AppCallSession(deps, scope, { next -> mutableState.value = next.copy(clipPath = mutableState.value?.clipPath) }, ui)
                .also { session = it }
        }

        private fun startService() {
            try {
                context.startForegroundService(Intent(context, VoipCallService::class.java))
            } catch (e: IllegalStateException) {
                // Android refused a foreground start from the background: the notification still rings, without it.
                Timber.w(e, "App call service not started")
                VoipNotifications(context).show(mutableState.value ?: return)
            }
        }
    }
