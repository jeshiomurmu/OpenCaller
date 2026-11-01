package io.github.jeshiomurmu.opencaller.core.telephony.otp

import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.InCallState
import javax.inject.Inject

/** Who a call's handle belongs to: the number and whether it is a contact (on the device; never the network). */
fun interface CallParties {
    /** The number of [handle] (`null` when hidden or not a number) and its party. */
    suspend fun of(
        handle: String?,
        hidden: Boolean,
    ): Pair<PhoneNumber?, CallParty>
}

/**
 * Feeds [CallActivityTracker] from the in-call service's calls (OpenCaller is the default phone app): a call that
 * appears starts, one that connects is answered, one that disappears or disconnects has ended. Conference parents are
 * left out; their calls are tracked one by one.
 */
class InCallFeed
    @Inject
    constructor(
        private val tracker: CallActivityTracker,
        private val parties: CallParties,
    ) {
        private val known = mutableSetOf<String>()

        /** Applies one in-call [state]; call from one coroutine at a time. */
        suspend fun apply(state: InCallState) {
            val live = state.calls.filter { it.isLive && !it.isConference }
            live.forEach { call ->
                val id = ID_PREFIX + call.id
                if (known.add(call.id)) {
                    val (number, party) = parties.of(call.handle, call.hidden)
                    tracker.started(id, number, party, incoming = call.direction == Direction.INCOMING)
                }
                if (call.status in CONNECTED || call.connectedAtMillis > 0) tracker.answered(id)
            }
            val gone = known - live.mapTo(mutableSetOf()) { it.id }
            gone.forEach { callId ->
                tracker.ended(ID_PREFIX + callId)
                known.remove(callId)
            }
        }

        private companion object {
            const val ID_PREFIX = "incall:"
            val CONNECTED = setOf(CallStatus.ACTIVE, CallStatus.HOLDING)
        }
    }
