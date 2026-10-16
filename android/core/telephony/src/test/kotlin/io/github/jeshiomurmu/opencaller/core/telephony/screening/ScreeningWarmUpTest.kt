package io.github.jeshiomurmu.opencaller.core.telephony.screening

import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreeningWarmUpTest {
    private val sources = FakeSources()
    private val normalizer = PhoneNumberNormalizer()

    @Test
    fun runsOneDryDecisionThroughEveryLookup() =
        runTest {
            val engine =
                ScreeningEngine(
                    sources.device(),
                    sources.lists(),
                    normalizer,
                    TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
                    testScheduler.timeSource,
                    ScreeningConfig(),
                )
            val result = ScreeningWarmUp(engine, normalizer) { sources.homeRegion }.warmUp()
            assertEquals(Decision.ALLOW, result?.decision)
            assertEquals("BD", result?.caller?.regionCode)
            assertEquals(1, sources.rulesRead)

            sources.homeRegion = null
            assertNull(ScreeningWarmUp(engine, normalizer) { sources.homeRegion }.warmUp())
        }
}
