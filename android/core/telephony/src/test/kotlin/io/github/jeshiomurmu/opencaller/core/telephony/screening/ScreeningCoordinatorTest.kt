package io.github.jeshiomurmu.opencaller.core.telephony.screening

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.CallerVerification
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEvent
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.testing.FakeClock
import io.github.jeshiomurmu.opencaller.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreeningCoordinatorTest {
    private val sources = FakeSources()
    private val clock = FakeClock()
    private val logged = mutableListOf<ScreeningEvent>()
    private val heard = mutableListOf<ScreeningEvent>()
    private val presented = mutableListOf<ScreeningResult>()
    private var logResult: AppResult<Long> = AppResult.Success(42)
    private val us = PhoneNumber("+12015550123")

    private fun TestScope.coordinator(presenter: CallerIdPresenter = CallerIdPresenter { presented += it }): ScreeningCoordinator {
        val dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler))
        val engine =
            ScreeningEngine(
                sources.device(),
                sources.lists(),
                PhoneNumberNormalizer(),
                dispatchers,
                testScheduler.timeSource,
                ScreeningConfig(),
            )
        return ScreeningCoordinator(
            engine = engine,
            log = {
                logged += it
                logResult
            },
            presenter = presenter,
            listeners = setOf(ScreeningEventListener { event, _ -> heard += event }),
            clock = clock,
            dispatchers = dispatchers,
        )
    }

    @Test
    fun blockedCallIsAnsweredLoggedAndGetsNoCard() =
        runTest {
            sources.rule(RuleType.EXACT, us.e164)
            val answers = mutableListOf<Decision>()
            coordinator().screen(ScreeningCall(us.e164, Direction.INCOMING, CallerVerification.PASSED)) { answers += it }
            advanceUntilIdle()

            assertEquals(listOf(Decision.BLOCK), answers)
            val event = logged.single()
            assertEquals(us, event.number)
            assertEquals(ScreeningReason.RULE, event.reason)
            assertEquals(CallerVerification.PASSED, event.verification)
            assertEquals(clock.nowMillis(), event.atMillis)
            assertEquals(42L, heard.single().id)
            assertTrue(presented.isEmpty())
        }

    @Test
    fun ringingCallGetsTheCard() =
        runTest {
            val answers = mutableListOf<Decision>()
            coordinator().screen(ScreeningCall(us.e164, Direction.INCOMING)) { answers += it }
            advanceUntilIdle()
            assertEquals(listOf(Decision.ALLOW), answers)
            assertEquals(us, presented.single().caller.number)
        }

    @Test
    fun emergencyCallsAreLoggedWithoutTheirNumberAndGetNoCard() =
        runTest {
            coordinator().screen(ScreeningCall("112", Direction.INCOMING)) {}
            advanceUntilIdle()
            assertEquals(ScreeningReason.EMERGENCY, logged.single().reason)
            assertNull(logged.single().number)
            assertTrue(presented.isEmpty())
        }

    @Test
    fun failuresAfterTheAnswerNeverAffectTheCall() =
        runTest {
            logResult = AppResult.Failure(AppError.Storage)
            val answers = mutableListOf<Decision>()
            val coordinator = coordinator(presenter = { error("no window") })
            coordinator.screen(ScreeningCall(us.e164, Direction.INCOMING)) {
                answers += it
                error("Telecom already unbound")
            }
            advanceUntilIdle()
            assertEquals(listOf(Decision.ALLOW), answers)
            assertTrue(heard.isEmpty())
        }
}
