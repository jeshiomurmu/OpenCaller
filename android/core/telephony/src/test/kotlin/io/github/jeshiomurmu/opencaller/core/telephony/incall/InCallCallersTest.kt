package io.github.jeshiomurmu.opencaller.core.telephony.incall

import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.screening.CallerLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.FakeSources
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningConfig
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningResult
import io.github.jeshiomurmu.opencaller.core.testing.FakeClock
import io.github.jeshiomurmu.opencaller.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The in-call screen's caller card: the screening answer for incoming calls, the device's own knowledge otherwise. */
@OptIn(ExperimentalCoroutinesApi::class)
class InCallCallersTest {
    private val sources = FakeSources().apply { homeRegion = "US" }
    private val clock = FakeClock()
    private val screened = ScreenedCalls(clock)
    private val us = PhoneNumber("+12015550123")

    private fun TestScope.callers() =
        InCallCallers(
            CallerLookup(
                sources.device(),
                sources.lists(),
                PhoneNumberNormalizer(),
                TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
                ScreeningConfig(),
            ),
            screened,
        )

    private fun call(
        handle: String?,
        direction: Direction = Direction.INCOMING,
        hidden: Boolean = false,
    ) = TrackedCall("1", handle, hidden, CallStatus.RINGING, direction)

    private fun screenedAs(name: String) =
        ScreeningResult(
            Decision.ALLOW,
            ScreeningReason.NO_MATCH,
            Direction.INCOMING,
            CallerInfo(us, displayName = name, nameSource = NameSource.SELF_REGISTERED),
        )

    @Test
    fun incomingCallsShowTheScreeningAnswer() =
        runTest {
            screened.remember(screenedAs("Dana"))
            assertEquals("Dana", callers().callerFor(call(us.e164)).displayName)
        }

    @Test
    fun outgoingCallsAndOldAnswersUseTheDevice() =
        runTest {
            sources.contacts[us] = "Alex"
            screened.remember(screenedAs("Dana"))
            assertEquals("Alex", callers().callerFor(call(us.e164, Direction.OUTGOING)).displayName)
            clock.advanceBy(6 * 60 * 1000L)
            assertEquals("Alex", callers().callerFor(call(us.e164)).displayName)
        }

    @Test
    fun hiddenCallersHaveNoNumber() =
        runTest {
            assertNull(callers().callerFor(call(us.e164, hidden = true)).number)
            assertNull(callers().callerFor(call(null)).number)
        }

    @Test
    fun outgoingResultsAreNotKept() {
        screened.remember(screenedAs("Dana").copy(direction = Direction.OUTGOING))
        assertNull(screened.latest(us))
    }
}
