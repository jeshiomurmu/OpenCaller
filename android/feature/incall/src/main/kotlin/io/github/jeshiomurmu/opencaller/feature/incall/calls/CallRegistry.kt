package io.github.jeshiomurmu.opencaller.feature.incall.calls

import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallAudio
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallController
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStateRepository
import io.github.jeshiomurmu.opencaller.core.telephony.incall.ConferenceAction
import io.github.jeshiomurmu.opencaller.core.telephony.incall.InCallState
import io.github.jeshiomurmu.opencaller.core.telephony.incall.TrackedCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.IdentityHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The calls Telecom gave [io.github.jeshiomurmu.opencaller.feature.incall.OpenCallerInCallService], with their ids,
 * and the commands the in-call screen sends them. Main thread only: Telecom calls the service there, and the screen
 * and the notification actions run there too.
 */
@Suppress("TooManyFunctions") // CallController's commands plus the service's bookkeeping.
@Singleton
internal class CallRegistry
    @Inject
    constructor() :
    CallStateRepository,
        CallController {
        private val calls = LinkedHashMap<String, TelecomCall>()
        private val ids = IdentityHashMap<Any, String>()
        private var nextId = 1L
        private var audio = CallAudio()
        private var canAddCall = false
        private var audioControl: AudioControl? = null
        private val mutableState = MutableStateFlow(InCallState())

        override val state: StateFlow<InCallState> = mutableState.asStateFlow()

        /** Starts tracking [call] and returns its id. */
        fun add(call: TelecomCall): String {
            ids[call.key]?.let { return it }
            val id = (nextId++).toString()
            calls[id] = call
            ids[call.key] = id
            call.observe(::publish)
            publish()
            return id
        }

        /** Stops tracking [call]; returns its last snapshot (for the after-call screen), or `null` if unknown. */
        fun remove(call: TelecomCall): TrackedCall? {
            val id = ids.remove(call.key) ?: return null
            val last = snapshot(id, call)
            calls.remove(id)
            call.stopObserving()
            publish()
            return last
        }

        /** The service is bound ([control]) or gone (`null`): audio commands go to it. */
        fun attach(control: AudioControl?) {
            audioControl = control
            if (control == null) {
                calls.values.forEach(TelecomCall::stopObserving)
                calls.clear()
                ids.clear()
                audio = CallAudio()
                canAddCall = false
                publish()
            }
        }

        /** New audio state from the service. */
        fun updateAudio(update: (CallAudio) -> CallAudio) {
            audio = update(audio)
            publish()
        }

        /** Whether another call can be added now. */
        fun updateCanAddCall(can: Boolean) {
            canAddCall = can
            publish()
        }

        /** The id of the call whose [TelecomCall.key] is [key], if tracked. */
        fun idOf(key: Any): String? = ids[key]

        override fun answer(callId: String) = on(callId, "answer") { it.answer() }

        override fun decline(callId: String) = on(callId, "decline") { it.reject(null) }

        override fun declineWithMessage(
            callId: String,
            message: String,
        ) = on(callId, "decline with message") { it.reject(message.trim().takeIf(String::isNotEmpty)) }

        override fun hangUp(callId: String) = on(callId, "hang up") { it.disconnect() }

        override fun hold(callId: String) = on(callId, "hold") { it.hold() }

        override fun resume(callId: String) = on(callId, "resume") { it.unhold() }

        override fun playTone(
            callId: String,
            digit: Char,
        ) {
            if (digit in TONE_DIGITS) on(callId, "tone") { it.playTone(digit) }
        }

        override fun stopTone(callId: String) = on(callId, "stop tone") { it.stopTone() }

        override fun perform(action: ConferenceAction) {
            when (action) {
                is ConferenceAction.Merge -> {
                    val other = calls[action.otherId] ?: return
                    on(action.callId, "merge") { it.conference(other) }
                }

                is ConferenceAction.MergeConference -> {
                    on(action.callId, "merge conference") { it.mergeConference() }
                }

                is ConferenceAction.SwapConference -> {
                    on(action.callId, "swap conference") { it.swapConference() }
                }

                is ConferenceAction.Resume -> {
                    resume(action.heldId)
                }
            }
        }

        override fun setMuted(muted: Boolean) {
            audioControl?.setMuted(muted) ?: Timber.w("Mute ignored: no in-call service")
        }

        override fun route(routeId: String) {
            if (audio.available.none { it.id == routeId }) return
            audioControl?.route(routeId) ?: Timber.w("Audio route ignored: no in-call service")
        }

        override fun selectSim(
            callId: String,
            simId: String,
        ) = on(callId, "select SIM") { it.selectSim(simId) }

        override fun refresh() = publish()

        private fun publish() {
            mutableState.value = InCallState(calls.map { (id, call) -> snapshot(id, call) }, audio, canAddCall)
        }

        private fun snapshot(
            id: String,
            call: TelecomCall,
        ): TrackedCall = call.snapshot(id) { ids[it] }

        // Telecom may have ended the call a moment ago; a command for it is then dropped, never a crash.
        @Suppress("TooGenericExceptionCaught")
        private inline fun on(
            callId: String,
            what: String,
            command: (TelecomCall) -> Unit,
        ) {
            val call = calls[callId] ?: return Timber.w("Call command %s ignored: the call is gone", what)
            try {
                command(call)
            } catch (e: RuntimeException) {
                Timber.w(e, "Call command %s failed", what)
            }
        }

        private companion object {
            const val TONE_DIGITS = "0123456789*#"
        }
    }
