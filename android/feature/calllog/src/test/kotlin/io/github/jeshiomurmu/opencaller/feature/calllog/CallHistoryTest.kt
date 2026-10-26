package io.github.jeshiomurmu.opencaller.feature.calllog

import io.github.jeshiomurmu.opencaller.core.model.CallLogEntry
import io.github.jeshiomurmu.opencaller.core.model.CallType
import io.github.jeshiomurmu.opencaller.core.model.CallerVerification
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEvent
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** TC-LOG-001 (logic): the missed, blocked and spam filters, search and grouping by number and day. */
class CallHistoryTest {
    private val history = CallHistory(ZoneOffset.UTC)
    private val ram = PhoneNumber("+8801712345678")
    private val spammer = PhoneNumber("+12025550199")
    private val silenced = PhoneNumber("+12025550150")
    private val friend = PhoneNumber("+447700900123")

    // 2026-10-24 09:00 UTC.
    private val morning = 1_792_832_400_000L
    private val minute = 60_000L
    private val day = 24 * 60 * minute

    private val calls =
        listOf(
            call(1, ram, CallType.MISSED, morning + 50 * minute, name = "Ram Kumar"),
            call(2, spammer, CallType.BLOCKED, morning + 40 * minute),
            call(3, silenced, CallType.MISSED, morning + 30 * minute),
            call(4, spammer, CallType.INCOMING, morning + 20 * minute),
            call(5, friend, CallType.OUTGOING, morning + 10 * minute),
            call(6, ram, CallType.INCOMING, morning, name = "Ram Kumar"),
            call(7, null, CallType.MISSED, morning - day),
            call(8, ram, CallType.OUTGOING, morning - day - minute, name = "Ram Kumar"),
        )

    private val events =
        listOf(
            event(spammer, Decision.BLOCK, ScreeningReason.SPAM_LIST, 0.92f, morning + 40 * minute + 1_000),
            event(silenced, Decision.SILENCE, ScreeningReason.NON_CONTACT, null, morning + 30 * minute - 500),
            // The answered call from the spammer earlier: allowed, but the list knew the number (suspicious).
            event(spammer, Decision.ALLOW, ScreeningReason.NO_MATCH, 0.45f, morning + 20 * minute),
            // Ram rang, a contact: nothing to add.
            event(ram, Decision.ALLOW, ScreeningReason.CONTACT, null, morning),
            // An outgoing call is never flagged.
            event(friend, Decision.ALLOW, ScreeningReason.OUTGOING, 0.9f, morning + 10 * minute, Direction.OUTGOING),
        )

    @Test
    fun tcLog001MissedLeavesOutBlockedAndSilencedCalls() {
        val flags = history.flags(calls, events)
        assertEquals(listOf(1L, 7L), history.filter(calls, flags, CallFilter.MISSED).map { it.id })
    }

    @Test
    fun tcLog001BlockedHasAndroidsAndScreeningsBlocks() {
        val flags = history.flags(calls, events)
        assertEquals(listOf(2L, 3L), history.filter(calls, flags, CallFilter.BLOCKED).map { it.id })
    }

    @Test
    fun tcLog001SpamHasListedAndSuspiciousCallers() {
        val flags = history.flags(calls, events)
        assertEquals(listOf(2L, 4L), history.filter(calls, flags, CallFilter.SPAM).map { it.id })
        assertEquals(0.92f, flags.getValue(2).spamScore)
        assertEquals(calls, history.filter(calls, flags, CallFilter.ALL))
    }

    @Test
    fun eventsMatchOnlyTheSameNumberCloseInTime() {
        val late = event(ram, Decision.BLOCK, ScreeningReason.RULE, null, morning + 50 * minute + CallHistory.MATCH_WINDOW_MILLIS + 1)
        val otherNumber = event(friend, Decision.BLOCK, ScreeningReason.RULE, null, morning + 50 * minute)
        assertTrue(history.flags(calls.take(1), listOf(late, otherNumber)).isEmpty())

        val hiddenBlock = event(null, Decision.BLOCK, ScreeningReason.HIDDEN, null, morning - day + 2_000)
        assertEquals(CallFlags(blocked = true), history.flags(calls, listOf(hiddenBlock))[7L])
    }

    @Test
    fun groupsAreOneNumberOnOneDayNewestFirst() {
        val flags = history.flags(calls, events)
        val groups = history.group(calls, flags)
        assertEquals(listOf(ram.e164, spammer.e164, silenced.e164, friend.e164, CallHistory.HIDDEN_KEY, ram.e164), groups.map { it.key })
        val today = groups.first()
        assertEquals(LocalDate.of(2026, 10, 24), today.day)
        assertEquals(listOf(1L, 6L), today.calls.map { it.id })
        assertEquals(2, today.count)
        assertEquals(CallType.MISSED, today.latest.type)
        val spam = groups[1]
        assertEquals(CallFlags(blocked = true, spam = true, spamScore = 0.92f), spam.flags)
        assertEquals(LocalDate.of(2026, 10, 23), groups.last().day)
    }

    @Test
    fun searchByNameOrDigits() {
        val names = mapOf(friend.e164 to "Alex Smith")
        val nameOf = { call: CallLogEntry -> call.number?.let { names[it.e164] } }
        assertEquals(listOf(1L, 6L, 8L), history.search(calls, "ram", nameOf).map { it.id })
        assertEquals(listOf(5L), history.search(calls, "SMITH", nameOf).map { it.id })
        assertEquals(listOf(2L, 3L, 4L), history.search(calls, "202 555", nameOf).map { it.id })
        assertEquals(calls, history.search(calls, "  ", nameOf))
    }

    private fun call(
        id: Long,
        number: PhoneNumber?,
        type: CallType,
        at: Long,
        name: String? = null,
    ) = CallLogEntry(id, number, number?.e164.orEmpty(), name, type, at, durationSeconds = 0)

    private fun event(
        number: PhoneNumber?,
        decision: Decision,
        reason: ScreeningReason,
        score: Float?,
        at: Long,
        direction: Direction = Direction.INCOMING,
    ) = ScreeningEvent(0, number, direction, decision, reason, score, CallerVerification.UNKNOWN, latencyMs = 10, atMillis = at)
}
