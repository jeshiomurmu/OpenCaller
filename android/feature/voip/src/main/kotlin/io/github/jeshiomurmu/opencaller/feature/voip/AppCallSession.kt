package io.github.jeshiomurmu.opencaller.feature.voip

import android.content.Context
import android.telecom.DisconnectCause
import androidx.core.telecom.CallException
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.data.voip.AppCallAction
import io.github.jeshiomurmu.opencaller.core.data.voip.AppCallRepository
import io.github.jeshiomurmu.opencaller.core.model.AppCallRoom
import io.github.jeshiomurmu.opencaller.core.model.AppCallState
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.livekit.android.events.RoomEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/** What a session needs from the app. */
internal class SessionDeps(
    val context: Context,
    val calls: AppCallRepository,
    val clock: Clock,
)

/**
 * One app call from start to end: the invite on the server, the self-managed Telecom call and the LiveKit room.
 * Runs on the main thread ([scope]); every change goes to [publish]. Ending is idempotent: whichever side ends the
 * call first (this phone, the other party, Telecom, the ring timeout) wins.
 */
@Suppress("TooManyFunctions") // One small function per event of the call: the user, the server, Telecom and the room.
internal class AppCallSession(
    private val deps: SessionDeps,
    private val scope: CoroutineScope,
    private val publish: (AppCallUi) -> Unit,
    initial: AppCallUi,
) : TelecomEvents {
    private var ui = initial
    private val room = LiveKitRoom(deps.context)
    private val telecom = TelecomCall(deps.context)

    /** The current state. */
    val state: AppCallUi get() = ui

    /** Rings here until [ringUntilMillis]. */
    fun ringIn(ringUntilMillis: Long) {
        register(incoming = true)
        timeout(ringUntilMillis, AppCallPhase.RINGING_IN, action = null)
    }

    /** Calls [number]: the invite, then the room, where the caller waits for the callee. */
    fun callOut(number: PhoneNumber) {
        scope.launch {
            when (val started = deps.calls.start(number)) {
                is AppResult.Failure -> {
                    finish(if (started.error == AppError.NotFound) AppCallEnd.UNREACHABLE else AppCallEnd.FAILED, null)
                }

                is AppResult.Success -> {
                    val (call, access) = started.value
                    update(ui.copy(phase = AppCallPhase.RINGING_OUT, inviteId = call.inviteId, name = call.name))
                    register(incoming = false)
                    timeout(call.ringUntilMillis, AppCallPhase.RINGING_OUT, AppCallAction.CANCEL)
                    join(access)
                }
            }
        }
    }

    /** The user answered on this phone. */
    fun answer() {
        scope.launch {
            telecom.answer()
            accept()
        }
    }

    /** The user hung up (or declined, or cancelled). */
    fun hangUp() {
        val action =
            when (ui.phase) {
                AppCallPhase.RINGING_IN -> AppCallAction.DECLINE
                AppCallPhase.CONNECTING, AppCallPhase.RINGING_OUT -> AppCallAction.CANCEL
                else -> AppCallAction.END
            }
        finish(AppCallEnd.HUNG_UP, action)
    }

    /** Mutes or unmutes the microphone. */
    fun setMuted(muted: Boolean) {
        scope.launch {
            room.setMuted(muted)
            update(ui.copy(muted = muted))
        }
    }

    /** Moves the audio to or from the speaker. */
    fun setSpeaker(on: Boolean) {
        scope.launch { telecom.setSpeaker(on) }
    }

    /** The server reported [state] for this call (a push from the other party). */
    fun onServer(state: AppCallState) {
        val next = ui.afterServer(state)
        next.end?.takeIf { ui.isLive }?.let { finish(it, null) }
    }

    override suspend fun onTelecomAnswer() = accept()

    override suspend fun onTelecomDisconnect(cause: DisconnectCause) =
        finish(AppCallEnd.HUNG_UP, if (ui.phase == AppCallPhase.RINGING_IN) AppCallAction.DECLINE else AppCallAction.END)

    override fun onSpeakerChanged(on: Boolean) = update(ui.copy(speaker = on))

    private suspend fun accept() {
        if (ui.phase != AppCallPhase.RINGING_IN) return
        val inviteId = ui.inviteId ?: return
        update(ui.copy(phase = AppCallPhase.ANSWERING))
        when (val access = deps.calls.act(inviteId, AppCallAction.ACCEPT).flatMap { deps.calls.room(inviteId) }) {
            is AppResult.Success -> join(access.value)
            is AppResult.Failure -> finish(AppCallEnd.FAILED, null)
        }
    }

    // LiveKit throws many unrelated exception types (WebRTC, signalling, I/O); all of them mean "not connected".
    @Suppress("TooGenericExceptionCaught")
    private suspend fun join(access: AppCallRoom) {
        try {
            room.join(access, scope, ::onRoomEvent)
            if (room.hasOtherParty) connected()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "App call: room not joined")
            finish(AppCallEnd.FAILED, AppCallAction.END)
        }
    }

    private fun onRoomEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.ParticipantConnected -> connected()
            is RoomEvent.ParticipantDisconnected -> finish(AppCallEnd.HUNG_UP, AppCallAction.END)
            is RoomEvent.Disconnected -> if (ui.isLive) finish(AppCallEnd.FAILED, AppCallAction.END)
            else -> Unit
        }
    }

    private fun connected() {
        if (ui.phase == AppCallPhase.ACTIVE || !ui.isLive) return
        update(ui.connected(deps.clock.nowMillis()))
        scope.launch { telecom.setActive() }
    }

    private fun register(incoming: Boolean) {
        scope.launch {
            try {
                telecom.run(ui.name ?: ui.number.orEmpty(), ui.number.orEmpty(), incoming, this@AppCallSession)
            } catch (e: CallException) {
                Timber.w(e, "Telecom refused the app call")
                finish(AppCallEnd.BUSY, if (incoming) AppCallAction.DECLINE else AppCallAction.CANCEL)
            }
        }
    }

    private fun timeout(
        untilMillis: Long,
        phase: AppCallPhase,
        action: AppCallAction?,
    ) {
        scope.launch {
            delay((untilMillis - deps.clock.nowMillis()).coerceAtLeast(0))
            if (ui.phase == phase) finish(AppCallEnd.MISSED, action)
        }
    }

    private fun finish(
        reason: AppCallEnd,
        action: AppCallAction?,
    ) {
        if (!ui.isLive) return
        update(ui.ended(reason))
        val inviteId = ui.inviteId
        scope.launch {
            if (action != null && inviteId != null) deps.calls.act(inviteId, action)
            telecom.disconnect(disconnectCode(reason))
            room.leave()
        }
    }

    private fun update(next: AppCallUi) {
        // Phases only (no number or ids): what a device log needs to follow a call, also in the emulator test.
        if (next.phase != ui.phase) Timber.i("App call %s%s", next.phase, next.end?.let { " ($it)" }.orEmpty())
        ui = next
        publish(next)
    }
}

/** The Telecom disconnect cause for [reason]. */
internal fun disconnectCode(reason: AppCallEnd): Int =
    when (reason) {
        AppCallEnd.HUNG_UP -> DisconnectCause.LOCAL
        AppCallEnd.DECLINED -> DisconnectCause.REJECTED
        AppCallEnd.MISSED -> DisconnectCause.MISSED
        AppCallEnd.BUSY -> DisconnectCause.BUSY
        AppCallEnd.UNREACHABLE, AppCallEnd.FAILED -> DisconnectCause.ERROR
    }
