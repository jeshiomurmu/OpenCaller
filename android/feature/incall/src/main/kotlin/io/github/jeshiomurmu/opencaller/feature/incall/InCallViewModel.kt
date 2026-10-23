package io.github.jeshiomurmu.opencaller.feature.incall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.telephony.incall.AudioRouteType
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallAudio
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallCapability
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallController
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStateRepository
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.ConferenceAction
import io.github.jeshiomurmu.opencaller.core.telephony.incall.InCallState
import io.github.jeshiomurmu.opencaller.core.telephony.incall.TrackedCall
import io.github.jeshiomurmu.opencaller.core.telephony.incall.mergeAction
import io.github.jeshiomurmu.opencaller.core.telephony.incall.swapAction
import io.github.jeshiomurmu.opencaller.feature.incall.calls.CallerDirectory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * One call on the in-call screen.
 *
 * @property caller who it is, or `null` while the lookup runs.
 * @property number the number for display, the raw handle when it isn't a phone number, `null` when hidden.
 */
data class CallView(
    val call: TrackedCall,
    val caller: CallerInfo?,
    val number: String?,
)

/**
 * The in-call screen.
 *
 * @property loaded false until the first state arrives (the screen doesn't close before that).
 * @property ringing a call waiting to be answered (alone, or while another call is in progress).
 * @property primary the call the controls act on.
 * @property held another call on hold.
 * @property merge what Merge does, or `null` to hide it.
 * @property swap what Swap does, or `null` to hide it.
 */
data class InCallUiState(
    val loaded: Boolean = false,
    val ringing: CallView? = null,
    val primary: CallView? = null,
    val held: CallView? = null,
    val audio: CallAudio = CallAudio(),
    val canAddCall: Boolean = false,
    val merge: ConferenceAction? = null,
    val swap: ConferenceAction? = null,
) {
    /** True once every call has ended: the screen says so and closes. */
    val isOver: Boolean get() = loaded && ringing == null && primary == null && held == null

    /** True when the primary call can be put on hold or taken off it. */
    val canHold: Boolean get() = primary?.call?.can(CallCapability.HOLD) == true

    /** True when the primary call can be muted (a call without the capability still shows the button, disabled). */
    val canMute: Boolean get() = primary?.call?.let { it.can(CallCapability.MUTE) || it.status == CallStatus.ACTIVE } == true

    /** True while a call plays through the earpiece and nothing rings: the screen turns off near the ear. */
    val usesEarpiece: Boolean get() = ringing == null && primary != null && audio.current?.type == AudioRouteType.EARPIECE
}

/** What the user did on the in-call screen. */
sealed interface InCallEvent {
    /** Answer [callId]. */
    data class Answer(
        val callId: String,
    ) : InCallEvent

    /** Decline [callId]. */
    data class Decline(
        val callId: String,
    ) : InCallEvent

    /** Decline [callId] and text [message] to the caller. */
    data class DeclineWithMessage(
        val callId: String,
        val message: String,
    ) : InCallEvent

    /** Hang up [callId]. */
    data class HangUp(
        val callId: String,
    ) : InCallEvent

    /** Hold or resume the primary call. */
    data object ToggleHold : InCallEvent

    /** Mute or unmute the microphone. */
    data object ToggleMute : InCallEvent

    /** Send call audio to [routeId]. */
    data class Route(
        val routeId: String,
    ) : InCallEvent

    /** A keypad key went down. */
    data class KeyDown(
        val digit: Char,
    ) : InCallEvent

    /** The keypad key went up. */
    data object KeyUp : InCallEvent

    /** Merge the calls. */
    data object Merge : InCallEvent

    /** Swap the calls. */
    data object Swap : InCallEvent

    /** Place [callId] with SIM [simId]. */
    data class SelectSim(
        val callId: String,
        val simId: String,
    ) : InCallEvent

    /** Read the calls again (a permission changed). */
    data object Refresh : InCallEvent
}

/** The in-call screen of the default phone app: the calls, their callers, and the commands. */
@HiltViewModel
class InCallViewModel
    @Inject
    constructor(
        calls: CallStateRepository,
        callers: CallerDirectory,
        private val numbers: CallNumbers,
        private val controller: CallController,
        clock: Clock,
    ) : ViewModel() {
        /** Screen state. */
        val uiState: StateFlow<InCallUiState> =
            combine(calls.state, callers.callers, ::toUi)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), InCallUiState())

        /** The time, once a second while the screen shows, for the call timer (Telecom's connect time is wall clock). */
        val nowMillis: StateFlow<Long> =
            flow {
                while (true) {
                    emit(clock.nowMillis())
                    delay(TICK_MILLIS)
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), clock.nowMillis())

        /** Handles [event]. */
        fun onEvent(event: InCallEvent) {
            when (event) {
                is InCallEvent.Answer -> controller.answer(event.callId)
                is InCallEvent.Decline -> controller.decline(event.callId)
                is InCallEvent.DeclineWithMessage -> controller.declineWithMessage(event.callId, event.message)
                is InCallEvent.HangUp -> controller.hangUp(event.callId)
                is InCallEvent.SelectSim -> controller.selectSim(event.callId, event.simId)
                InCallEvent.Refresh -> controller.refresh()
                else -> onControl(event, uiState.value)
            }
        }

        // The controls of the call in progress.
        private fun onControl(
            event: InCallEvent,
            state: InCallUiState,
        ) {
            when (event) {
                InCallEvent.ToggleHold -> {
                    toggleHold(state)
                }

                InCallEvent.ToggleMute -> {
                    controller.setMuted(!state.audio.muted)
                }

                is InCallEvent.Route -> {
                    controller.route(event.routeId)
                }

                is InCallEvent.KeyDown -> {
                    state.primary?.let { controller.playTone(it.call.id, event.digit) }
                }

                InCallEvent.KeyUp -> {
                    state.primary?.let { controller.stopTone(it.call.id) }
                }

                InCallEvent.Merge -> {
                    state.merge?.let(controller::perform)
                }

                InCallEvent.Swap -> {
                    state.swap?.let(controller::perform)
                }

                else -> {
                    // Call and SIM commands are handled in onEvent.
                }
            }
        }

        private fun toggleHold(state: InCallUiState) {
            val primary = state.primary?.call?.takeIf { state.canHold } ?: return
            if (primary.status == CallStatus.HOLDING) controller.resume(primary.id) else controller.hold(primary.id)
        }

        private fun toUi(
            state: InCallState,
            callers: Map<String, CallerInfo>,
        ): InCallUiState {
            fun view(call: TrackedCall?) = call?.let { CallView(it, callers[it.id], numbers.number(it, callers[it.id])) }
            return InCallUiState(
                loaded = true,
                ringing = view(state.ringing),
                primary = view(state.primary),
                held = view(state.held),
                audio = state.audio,
                canAddCall = state.canAddCall,
                merge = state.mergeAction(),
                swap = state.swapAction(),
            )
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val TICK_MILLIS = 1_000L
        }
    }
