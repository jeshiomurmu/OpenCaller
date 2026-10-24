package io.github.jeshiomurmu.opencaller.feature.calllog

import io.github.jeshiomurmu.opencaller.core.model.CallLogEntry
import io.github.jeshiomurmu.opencaller.core.model.CallType
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEvent
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.model.SpamThreshold
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

/** The call log's filters (docs/prd.md F-CM-03, TC-LOG-001). */
enum class CallFilter {
    /** Every call. */
    ALL,

    /** Unanswered incoming calls that weren't blocked or silenced. */
    MISSED,

    /** Calls OpenCaller or Android blocked or silenced. */
    BLOCKED,

    /** Calls screening flagged as spam or suspicious. */
    SPAM,
}

/**
 * What OpenCaller's screening log adds to one call of Android's call log.
 *
 * @property spamScore the community score screening saw for the caller, if any.
 */
data class CallFlags(
    val blocked: Boolean = false,
    val spam: Boolean = false,
    val spamScore: Float? = null,
)

/**
 * One row of the call log: the calls with one number on one day, newest first.
 *
 * @property key the number the calls share (E.164, the logged dial string, or [CallHistory.HIDDEN_KEY]).
 * @property flags blocked or spam if any call in the group is.
 */
data class CallGroup(
    val key: String,
    val day: LocalDate,
    val calls: List<CallLogEntry>,
    val flags: CallFlags,
) {
    /** The newest call. */
    val latest: CallLogEntry get() = calls.first()

    /** How many calls the row stands for. */
    val count: Int get() = calls.size
}

/**
 * Turns Android's call log and OpenCaller's screening log into the rows of the call log screen. Pure: both logs are
 * read on the device and joined here (nothing leaves the phone, docs/architecture.md §10). A call matches a screening
 * event of the same incoming number (or of a hidden caller, for a hidden call) within [MATCH_WINDOW_MILLIS].
 */
class CallHistory(
    private val zone: ZoneId,
) {
    /** What the screening [events] say about each of [calls], by call id. Calls with nothing to add are left out. */
    fun flags(
        calls: List<CallLogEntry>,
        events: List<ScreeningEvent>,
    ): Map<Long, CallFlags> {
        val incoming = events.filter { it.direction == Direction.INCOMING }.groupBy { it.number?.e164 }
        return calls
            .mapNotNull { call ->
                val event = if (call.type.isIncoming) matching(call, incoming[call.number?.e164].orEmpty()) else null
                val flags = flagsOf(call, event)
                if (flags == CallFlags()) null else call.id to flags
            }.toMap()
    }

    /** [calls] that [filter] keeps. */
    fun filter(
        calls: List<CallLogEntry>,
        flags: Map<Long, CallFlags>,
        filter: CallFilter,
    ): List<CallLogEntry> =
        calls.filter { call ->
            val flag = flags[call.id] ?: CallFlags()
            when (filter) {
                CallFilter.ALL -> true
                CallFilter.MISSED -> call.type == CallType.MISSED && !flag.blocked
                CallFilter.BLOCKED -> flag.blocked
                CallFilter.SPAM -> flag.spam
            }
        }

    /**
     * [calls] matching [query]: the name ([nameOf], else the name Android cached) contains it, ignoring case, or the
     * number's digits contain its digits. A blank query keeps every call.
     */
    fun search(
        calls: List<CallLogEntry>,
        query: String,
        nameOf: (CallLogEntry) -> String?,
    ): List<CallLogEntry> {
        val text = query.trim()
        if (text.isEmpty()) return calls
        val digits = text.filter(::isDigit)
        return calls.filter { call ->
            val name = nameOf(call) ?: call.cachedName
            name?.contains(text, ignoreCase = true) == true ||
                (digits.isNotEmpty() && (call.number?.e164 ?: call.dialString).filter(::isDigit).contains(digits))
        }
    }

    /** [calls] (newest first) as rows: one per number and day, newest row first. */
    fun group(
        calls: List<CallLogEntry>,
        flags: Map<Long, CallFlags>,
    ): List<CallGroup> =
        calls
            .groupBy { keyOf(it) to dayOf(it) }
            .map { (key, groupCalls) ->
                val groupFlags = groupCalls.mapNotNull { flags[it.id] }
                CallGroup(
                    key = key.first,
                    day = key.second,
                    calls = groupCalls,
                    flags =
                        CallFlags(
                            blocked = groupFlags.any { it.blocked },
                            spam = groupFlags.any { it.spam },
                            spamScore = groupFlags.mapNotNull { it.spamScore }.maxOrNull(),
                        ),
                )
            }.sortedByDescending { it.latest.atMillis }

    /** The local day of [call]. */
    fun dayOf(call: CallLogEntry): LocalDate = Instant.ofEpochMilli(call.atMillis).atZone(zone).toLocalDate()

    private fun keyOf(call: CallLogEntry): String = call.number?.e164 ?: call.dialString.ifBlank { HIDDEN_KEY }

    private fun matching(
        call: CallLogEntry,
        candidates: List<ScreeningEvent>,
    ): ScreeningEvent? =
        candidates
            .filter { abs(it.atMillis - call.atMillis) <= MATCH_WINDOW_MILLIS }
            .minByOrNull { abs(it.atMillis - call.atMillis) }

    private fun flagsOf(
        call: CallLogEntry,
        event: ScreeningEvent?,
    ): CallFlags {
        val score = event?.spamScore
        val spam =
            event != null &&
                (event.reason in SPAM_REASONS || (score != null && score >= SpamThreshold.LOW.minimumScore))
        val blocked = call.type == CallType.BLOCKED || event?.decision == Decision.BLOCK || event?.decision == Decision.SILENCE
        return CallFlags(blocked = blocked, spam = spam, spamScore = score)
    }

    private val CallType.isIncoming: Boolean get() = this != CallType.OUTGOING

    /** Matching rules. */
    companion object {
        /** A screening event and a logged call this close in time are the same call. */
        const val MATCH_WINDOW_MILLIS: Long = 2 * 60 * 1000

        /** The group key of hidden callers. */
        const val HIDDEN_KEY: String = "hidden"

        private val SPAM_REASONS = setOf(ScreeningReason.SPAM_LIST, ScreeningReason.NETWORK)
    }
}

private fun isDigit(c: Char): Boolean = c in '0'..'9'
