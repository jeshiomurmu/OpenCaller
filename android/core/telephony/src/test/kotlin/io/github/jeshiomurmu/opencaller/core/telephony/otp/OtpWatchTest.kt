package io.github.jeshiomurmu.opencaller.core.telephony.otp

import io.github.jeshiomurmu.opencaller.core.model.FamilyAlertKind
import io.github.jeshiomurmu.opencaller.core.model.MessageRisk
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.RecordingAlarm
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.otpWatch
import io.github.jeshiomurmu.opencaller.core.testing.FakeClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpWatchTest {
    private val clock = FakeClock()
    private val calls = CallActivityTracker(clock)
    private val alarm = RecordingAlarm()
    private val earlier = mutableListOf<Pair<Long, String>>()
    private val range = PhoneNumber("+15550100777")
    private val reported = mutableListOf<FamilyAlertKind>()
    private val watch =
        otpWatch(
            calls,
            alarm,
            clock,
            recent = { since -> earlier.filter { it.first >= since } },
            ranges = { if (it == range) "Example Flash" else null },
            family = { reported += it },
        )
    private val stranger = PhoneNumber("+15550109999")

    private fun conversation(
        id: String,
        party: CallParty,
    ) {
        calls.started(id, stranger, party, incoming = true)
        calls.answered(id)
    }

    @Test
    fun tcOtp006ACodeDuringACallWithAContactIsFine() =
        runTest {
            conversation("c1", CallParty.CONTACT)
            assertEquals(emptySet<MessageRisk>(), watch.received("thread:1", "Bank"))
            assertTrue(alarm.duringCall.isEmpty())
            assertTrue(reported.isEmpty()) // no warning, nothing for the family admin either
        }

    @Test
    fun aCodeUpToTwoMinutesAfterACallWithAStrangerStillWarns() =
        runTest {
            conversation("c1", CallParty.STRANGER)
            clock.advanceBy(30_000)
            calls.ended("c1")
            clock.advanceBy(OtpRules.AFTER_CALL_MILLIS)
            assertEquals(setOf(MessageRisk.OTP_DURING_CALL), watch.received("thread:1", "Bank"))
            assertEquals(listOf<Pair<String?, Boolean>>("Bank" to false), alarm.duringCall)
            // TC-FAM-002: the warning is reported (kind only; the family reporter sends it if the member shares alerts).
            assertEquals(listOf(FamilyAlertKind.OTP_DURING_CALL), reported)
            clock.advanceBy(1)
            assertEquals(emptySet<MessageRisk>(), watch.received("thread:1", "Bank"))
        }

    @Test
    fun aMissedCallIsNoConversationButACallTheUserPlacedIs() {
        calls.started("missed", stranger, CallParty.STRANGER, incoming = true)
        calls.ended("missed")
        assertNull(OtpRules.riskyCall(calls.recent(), clock.nowMillis()))
        calls.started("out", stranger, CallParty.STRANGER, incoming = false)
        assertEquals("out", OtpRules.riskyCall(calls.recent(), clock.nowMillis())?.id)
    }

    @Test
    fun tcOtp007AFlashCallFromAVerificationRangeDuringAStrangersCallWarns() =
        runTest {
            conversation("c1", CallParty.STRANGER)
            calls.started("flash", range, CallParty.STRANGER, incoming = true)
            clock.advanceBy(2_000)
            calls.ended("flash")
            val flash = calls.recent().single { it.id == "flash" }
            assertTrue(OtpRules.isFlashCall(flash))
            watch.callEnded(flash)
            assertEquals(listOf<Pair<String?, Boolean>>("Example Flash" to true), alarm.duringCall)
            assertEquals(listOf(FamilyAlertKind.OTP_DURING_CALL), reported)
        }

    @Test
    fun aLongMissedCallOrOneOutsideTheRangesIsNoFlashCall() =
        runTest {
            conversation("c1", CallParty.STRANGER)
            calls.started("long", range, CallParty.STRANGER, incoming = true)
            clock.advanceBy(OtpRules.FLASH_CALL_MILLIS + 1)
            calls.ended("long")
            watch.callEnded(calls.recent().single { it.id == "long" })
            calls.started("other", stranger, CallParty.STRANGER, incoming = true)
            calls.ended("other")
            watch.callEnded(calls.recent().single { it.id == "other" })
            assertTrue(alarm.duringCall.isEmpty())
        }

    @Test
    fun tcOtp009ThreeCodesFromTwoSendersInTenMinutesAreABurst() =
        runTest {
            val now = clock.nowMillis()
            earlier += (now - 9 * 60_000L) to "thread:1"
            earlier += (now - 4 * 60_000L) to "thread:1"
            assertEquals(setOf(MessageRisk.OTP_BURST), watch.received("thread:2", "Shop"))
            assertEquals(listOf(3), alarm.bursts)
            assertEquals(listOf(FamilyAlertKind.OTP_BURST), reported)
        }

    @Test
    fun codesFromOneSenderOrSpreadOutAreNoBurst() {
        val now = 100_000_000L
        assertFalse(OtpRules.isBurst(listOf(now - 1 to "a", now - 2 to "a", now to "a"), now))
        assertFalse(OtpRules.isBurst(listOf(now - 11 * 60_000L to "a", now - 1 to "b", now to "c"), now))
        assertTrue(OtpRules.isBurst(listOf(now - 10 * 60_000L to "a", now - 1 to "b", now to "b"), now))
    }
}
