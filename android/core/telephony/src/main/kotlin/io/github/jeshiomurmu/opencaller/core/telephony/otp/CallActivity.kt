package io.github.jeshiomurmu.opencaller.core.telephony.otp

import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Who is on the other end of a call, for the OTP guard. A caller OpenCaller can't identify counts as a stranger. */
enum class CallParty {
    /** Someone in the user's contacts. */
    CONTACT,

    /** Not in the contacts, hidden, or unknown. */
    STRANGER,
}

/**
 * A call as the OTP guard sees it (docs/architecture.md §4.5). [answeredAtMillis] is set once the call connects;
 * [endedAtMillis] once it is over. Kept in memory only.
 */
data class ObservedCall(
    val id: String,
    val number: PhoneNumber?,
    val party: CallParty,
    val incoming: Boolean,
    val startedAtMillis: Long,
    val answeredAtMillis: Long? = null,
    val endedAtMillis: Long? = null,
) {
    /** True while the call is ringing or connected. */
    val isLive: Boolean get() = endedAtMillis == null

    /** Never prints the number. */
    override fun toString(): String = "ObservedCall(id=$id, party=$party, incoming=$incoming, live=$isLive)"
}

/**
 * The calls of the last few minutes, fed by the in-call service (default phone app) or the phone's call state
 * (screening-only mode). Nothing is written anywhere.
 */
@Singleton
class CallActivityTracker
    @Inject
    constructor(
        private val clock: Clock,
    ) {
        private val calls = ConcurrentHashMap<String, ObservedCall>()
        private val endedFlow = MutableSharedFlow<ObservedCall>(extraBufferCapacity = BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        /** Calls as they end (for flash-call detection). */
        val ended: SharedFlow<ObservedCall> = endedFlow

        /** A call [id] started ringing or dialling. A second report for the same id updates what is known. */
        fun started(
            id: String,
            number: PhoneNumber?,
            party: CallParty,
            incoming: Boolean,
        ) {
            prune()
            calls.compute(id) { _, known ->
                known?.copy(number = number ?: known.number, party = party)
                    ?: ObservedCall(id, number, party, incoming, startedAtMillis = clock.nowMillis())
            }
        }

        /** Call [id] connected. */
        fun answered(id: String) {
            calls.computeIfPresent(id) { _, call -> call.answeredAtMillis?.let { call } ?: call.copy(answeredAtMillis = clock.nowMillis()) }
        }

        /** Call [id] is over. */
        fun ended(id: String) {
            val known = calls[id]
            if (known == null || known.endedAtMillis != null) return
            val call = known.copy(endedAtMillis = clock.nowMillis())
            calls[id] = call
            endedFlow.tryEmit(call)
        }

        /** Live calls and calls that ended within the guard's window. */
        fun recent(): List<ObservedCall> {
            prune()
            return calls.values.sortedBy { it.startedAtMillis }
        }

        private fun prune() {
            val now = clock.nowMillis()
            calls.values.removeIf { call -> call.endedAtMillis?.let { now - it > KEEP_MILLIS } == true }
        }

        private companion object {
            // Longer than the guard's 2-minute window, so a call is never forgotten while it still counts.
            const val KEEP_MILLIS = 5 * 60 * 1000L
            const val BUFFER = 8
        }
    }
