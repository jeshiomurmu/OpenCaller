package io.github.jeshiomurmu.opencaller.core.telephony.screening

import io.github.jeshiomurmu.opencaller.core.data.CachedReputation
import io.github.jeshiomurmu.opencaller.core.model.LineType
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.VerdictSource
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [CallerLookup]: what the in-call screen shows for a call (outgoing calls included), on-device only. */
@OptIn(ExperimentalCoroutinesApi::class)
class InCallDescriptionTest {
    private val sources = FakeSources().apply { homeRegion = "US" }
    private val us = PhoneNumber("+12015550123")

    private fun TestScope.engine(config: ScreeningConfig = ScreeningConfig()) =
        CallerLookup(
            device = sources.device(),
            lists = sources.lists(),
            normalizer = PhoneNumberNormalizer(),
            dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
            config = config,
        )

    @Test
    fun contactsShowTheirName() =
        runTest {
            sources.contacts[us] = "Alex"
            val caller = engine().describe("(201) 555-0123")
            assertEquals(us, caller.number)
            assertEquals("Alex", caller.displayName)
            assertEquals(NameSource.CONTACT, caller.nameSource)
        }

    @Test
    fun spamListAndCacheAnnotateWithoutTheNetwork() =
        runTest {
            var networkCalls = 0
            sources.network =
                NetworkLookup {
                    networkCalls++
                    null
                }
            sources.spam(us, 0.9f)
            sources.reputation[us] = reputation(lineType = LineType.VOIP)
            val caller = engine().describe(us.e164)
            assertEquals(RiskLevel.LIKELY_SPAM, caller.spamVerdict?.level)
            assertEquals(VerdictSource.SPAM_LIST, caller.spamVerdict?.source)
            assertEquals("US", caller.regionCode)
            assertEquals(LineType.VOIP, caller.lineType)
            assertEquals(0, networkCalls)
        }

    @Test
    fun aContactsOwnNumberCanBeCheckedAgainstTheSpamList() =
        runTest {
            sources.contacts[us] = "Alex"
            sources.spam(us, 0.9f)
            val caller = engine().describe(us.e164, includeContacts = false)
            assertNull(caller.nameSource)
            assertEquals(RiskLevel.LIKELY_SPAM, caller.spamVerdict?.level)
        }

    @Test
    fun emergencyAndHiddenCallsHaveNoNumber() =
        runTest {
            assertNull(engine().describe("911").number)
            assertNull(engine().describe(null).number)
            assertNull(engine().describe("  ").number)
        }

    @Test
    fun slowLookupsFallBackToTheNumberAlone() =
        runTest {
            sources.contacts[us] = "Alex"
            sources.lookupDelayMillis = 5_000
            val caller = engine(ScreeningConfig(budgetMillis = 100)).describe(us.e164)
            assertEquals(us, caller.number)
            assertNull(caller.displayName)
            assertEquals("US", caller.regionCode)
        }

    @Test
    fun failuresFallBackToTheNumberAlone() =
        runTest {
            sources.failWith = IllegalStateException("settings unreadable")
            val caller = engine().describe(us.e164)
            assertEquals(us, caller.number)
            assertNull(caller.spamVerdict)
        }

    private fun reputation(lineType: LineType) =
        CachedReputation(
            displayName = null,
            nameSource = null,
            spamScore = null,
            spamCategory = null,
            reportCount = 0,
            verifiedBusinessId = null,
            logoUrl = null,
            callReason = null,
            lineType = lineType,
        )
}
