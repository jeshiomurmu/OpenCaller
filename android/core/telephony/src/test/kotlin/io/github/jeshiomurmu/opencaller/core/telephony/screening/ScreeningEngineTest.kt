package io.github.jeshiomurmu.opencaller.core.telephony.screening

import io.github.jeshiomurmu.opencaller.core.common.DefaultDispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.CachedReputation
import io.github.jeshiomurmu.opencaller.core.model.CallerVerification
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.model.SpamThreshold
import io.github.jeshiomurmu.opencaller.core.model.VerdictSource
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.TimeSource

/** ScreeningEngine decision order (docs/architecture.md §4.1) and the TC-BLK unit cases. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreeningEngineTest {
    private val sources = FakeSources()
    private val us = PhoneNumber("+12015550123")
    private val bd = PhoneNumber("+8801712345678")
    private val normalizer = PhoneNumberNormalizer()

    private fun TestScope.engine(
        config: ScreeningConfig = ScreeningConfig(),
        regexClock: () -> Long = System::nanoTime,
    ) = ScreeningEngine(
        device = sources.device(),
        lists = sources.lists(),
        normalizer = normalizer,
        dispatchers = TestDispatcherProvider(StandardTestDispatcher(testScheduler)),
        timeSource = testScheduler.timeSource,
        config = config,
        regexClock = regexClock,
    )

    private suspend fun ScreeningEngine.incoming(
        number: PhoneNumber,
        verification: CallerVerification = CallerVerification.UNKNOWN,
    ) = screen(ScreeningCall(number.e164, Direction.INCOMING, verification))

    @Test
    fun unknownNumberRingsWithItsCountryAndLineType() =
        runTest {
            val result = engine().incoming(us)
            assertEquals(Decision.ALLOW, result.decision)
            assertEquals(ScreeningReason.NO_MATCH, result.reason)
            assertEquals(us, result.caller.number)
            assertEquals("US", result.caller.regionCode)
            assertTrue(result.showsCallerId)
        }

    @Test
    fun tcBlk001ContactRingsEvenWhenABlockRuleCoversIt() =
        runTest {
            sources.contacts[us] = "Alex"
            sources.rule(RuleType.PREFIX, "+1201")
            val result = engine().incoming(us)
            assertEquals(Decision.ALLOW, result.decision)
            assertEquals(ScreeningReason.CONTACT, result.reason)
            assertEquals("Alex", result.caller.displayName)
            assertEquals(NameSource.CONTACT, result.caller.nameSource)
        }

    @Test
    fun tcBlk002ExactRuleBlocks() =
        runTest {
            val rule = sources.rule(RuleType.EXACT, us.e164)
            val result = engine().incoming(us)
            assertEquals(Decision.BLOCK, result.decision)
            assertEquals(ScreeningReason.RULE, result.reason)
            assertEquals(rule, result.matchedRule)
            assertEquals(false, result.showsCallerId)
        }

    @Test
    fun tcBlk003PrefixRuleBlocksOnlyMatchingNumbers() =
        runTest {
            sources.rule(RuleType.PREFIX, "+1555123")
            val engine = engine()
            assertEquals(Decision.BLOCK, engine.incoming(PhoneNumber("+15551230001")).decision)
            assertEquals(Decision.ALLOW, engine.incoming(PhoneNumber("+15551240001")).decision)
        }

    @Test
    fun countryRuleAndSilenceRule() =
        runTest {
            sources.rule(RuleType.COUNTRY, "US", RuleAction.SILENCE)
            val engine = engine()
            val silenced = engine.incoming(us)
            assertEquals(Decision.SILENCE, silenced.decision)
            assertEquals(ScreeningReason.RULE, silenced.reason)
            assertTrue(silenced.showsCallerId)
            assertEquals(Decision.ALLOW, engine.incoming(bd).decision)
        }

    @Test
    fun blockBeatsSilenceAndDisabledRulesAreIgnored() =
        runTest {
            sources.rule(RuleType.PREFIX, "+1", RuleAction.SILENCE)
            sources.rule(RuleType.EXACT, us.e164, RuleAction.BLOCK)
            sources.rule(RuleType.EXACT, us.e164, RuleAction.ALLOW, enabled = false)
            assertEquals(Decision.BLOCK, engine().incoming(us).decision)
        }

    @Test
    fun tcBlk004CatastrophicRegexTimesOutAndTheCallRings() =
        runTest {
            sources.rule(RuleType.REGEX, "(a+)+$")
            sources.rule(RuleType.REGEX, """^\+1""")
            // Every read of the input is past the regex deadline, as with a catastrophic pattern.
            var now = 0L
            val result = engine(regexClock = { now.also { now += 1_000_000_000L } }).incoming(us)
            assertEquals(Decision.ALLOW, result.decision)
            assertEquals(ScreeningReason.NO_MATCH, result.reason)
        }

    @Test
    fun regexRuleMatchesWithinBudget() =
        runTest {
            sources.rule(RuleType.REGEX, """^\+1201555""")
            assertEquals(Decision.BLOCK, engine().incoming(us).decision)
            assertEquals(Decision.ALLOW, engine().incoming(bd).decision)
        }

    @Test
    fun tcBlk005AllowRuleBeatsBlockRule() =
        runTest {
            sources.rule(RuleType.PREFIX, "+1201", RuleAction.BLOCK)
            val allow = sources.rule(RuleType.EXACT, us.e164, RuleAction.ALLOW)
            val result = engine().incoming(us)
            assertEquals(Decision.ALLOW, result.decision)
            assertEquals(ScreeningReason.ALLOW_RULE, result.reason)
            assertEquals(allow, result.matchedRule)
        }

    @Test
    fun hiddenNumbersAreBlockedOnlyWhenTheToggleIsOn() =
        runTest {
            val engine = engine()
            val hidden = ScreeningCall(handle = null, direction = Direction.INCOMING)
            assertEquals(ScreeningReason.NO_MATCH, engine.screen(hidden).reason)
            sources.prefs = sources.prefs.copy(blockHiddenNumbers = true)
            val blocked = engine.screen(hidden.copy(handle = " "))
            assertEquals(Decision.BLOCK, blocked.decision)
            assertEquals(ScreeningReason.HIDDEN, blocked.reason)
            assertNull(blocked.caller.number)
        }

    @Test
    fun tcBlk006ForeignNumbersUnlessTheirCountryIsAllowed() =
        runTest {
            sources.prefs = sources.prefs.copy(blockForeignNumbers = true)
            val engine = engine()
            val blocked = engine.incoming(us)
            assertEquals(Decision.BLOCK, blocked.decision)
            assertEquals(ScreeningReason.FOREIGN, blocked.reason)
            assertEquals(Decision.ALLOW, engine.incoming(bd).decision)

            sources.prefs = sources.prefs.copy(allowedRegionCodes = setOf("US"))
            assertEquals(Decision.ALLOW, engine.incoming(us).decision)
        }

    @Test
    fun foreignBlockingFailsOpenWithoutAHomeRegion() =
        runTest {
            sources.prefs = sources.prefs.copy(blockForeignNumbers = true)
            sources.homeRegion = null
            assertEquals(Decision.ALLOW, engine().incoming(us).decision)
        }

    @Test
    fun tcBlk008NonContactsAreBlockedButVerifiedBusinessesRing() =
        runTest {
            sources.prefs = sources.prefs.copy(blockNonContacts = true)
            sources.verifiedBusinesses += bd
            val engine = engine()
            val blocked = engine.incoming(us)
            assertEquals(Decision.BLOCK, blocked.decision)
            assertEquals(ScreeningReason.NON_CONTACT, blocked.reason)

            val business = engine.incoming(bd)
            assertEquals(Decision.ALLOW, business.decision)
            assertTrue(business.caller.isVerifiedBusiness)
        }

    @Test
    fun nonContactsAreNeverBlockedWithoutTheContactsPermission() =
        runTest {
            sources.prefs = sources.prefs.copy(blockNonContacts = true)
            sources.canReadContacts = false
            assertEquals(Decision.ALLOW, engine().incoming(us).decision)
        }

    @Test
    fun tcBlk009SpamListThreshold() =
        runTest {
            sources.prefs = sources.prefs.copy(spamThreshold = SpamThreshold.HIGH)
            val suspicious = PhoneNumber("+12015550101")
            sources.spam(us, 0.9f)
            sources.spam(suspicious, 0.7f)
            val engine = engine()

            val rejected = engine.incoming(us)
            assertEquals(Decision.BLOCK, rejected.decision)
            assertEquals(ScreeningReason.SPAM_LIST, rejected.reason)
            assertEquals(0.9f, rejected.caller.spamVerdict?.score)

            val rings = engine.incoming(suspicious)
            assertEquals(Decision.ALLOW, rings.decision)
            assertEquals(RiskLevel.SUSPICIOUS, rings.caller.spamVerdict?.level)

            sources.prefs = sources.prefs.copy(spamThreshold = SpamThreshold.MEDIUM, blockTopSpammers = false)
            assertEquals(Decision.SILENCE, engine.incoming(suspicious).decision)
        }

    @Test
    fun tcBlk010AnyFailureLetsTheCallRing() =
        runTest {
            sources.failWith = IllegalStateException("settings unreadable")
            val result = engine().incoming(us)
            assertEquals(Decision.ALLOW, result.decision)
            assertEquals(ScreeningReason.ERROR, result.reason)
        }

    @Test
    fun tcBlk011SlowLookupIsAbandonedAtTheBudget() =
        runTest {
            sources.network =
                NetworkLookup {
                    delay(3_000)
                    null
                }
            // A lookup limit above the decision budget, so the budget itself is what stops the decision.
            val result = engine(ScreeningConfig(networkBudgetMillis = 5_000)).incoming(us)
            assertEquals(Decision.ALLOW, result.decision)
            assertEquals(ScreeningReason.TIMEOUT, result.reason)
            assertTrue("decided at $currentTime ms", currentTime <= ScreeningConfig.DEFAULT_BUDGET_MILLIS)
            assertEquals(ScreeningConfig.DEFAULT_BUDGET_MILLIS.toInt(), result.latencyMs)
        }

    // TC-LOOK-001: a server that answers after 2 s is cut off at 800 ms; the call rings with the phone's own info.
    @Test
    fun tcLook001SlowLiveLookupStillRingsWithLocalInfo() =
        runTest {
            sources.spam(us, 0.5f)
            sources.network =
                NetworkLookup {
                    delay(2_000)
                    NetworkVerdict(0.99f, "scam", 500, null, null)
                }
            val result = engine().incoming(us)
            assertEquals(Decision.ALLOW, result.decision)
            assertEquals(ScreeningReason.NO_MATCH, result.reason)
            assertEquals(ScreeningConfig.DEFAULT_NETWORK_BUDGET_MILLIS, currentTime)
            assertEquals(RiskLevel.SUSPICIOUS, result.caller.spamVerdict?.level)
            assertEquals(VerdictSource.SPAM_LIST, result.caller.spamVerdict?.source)
        }

    @Test
    fun liveLookupBlocksAtTheUsersThreshold() =
        runTest {
            sources.network = NetworkLookup { NetworkVerdict(0.9f, "scam", 40, null, null) }
            val blocked = engine().incoming(us)
            assertEquals(Decision.BLOCK, blocked.decision)
            assertEquals(ScreeningReason.NETWORK, blocked.reason)
            assertEquals(VerdictSource.LIVE_LOOKUP, blocked.caller.spamVerdict?.source)
            assertEquals(40, blocked.caller.spamVerdict?.reportCount)

            sources.prefs = sources.prefs.copy(blockTopSpammers = false)
            assertEquals(Decision.SILENCE, engine().incoming(us).decision)

            // Below the threshold it only shows on the card.
            sources.network = NetworkLookup { NetworkVerdict(0.5f, "scam", 3, null, null, fromCache = true) }
            val shown = engine().incoming(us)
            assertEquals(Decision.ALLOW, shown.decision)
            assertEquals(RiskLevel.SUSPICIOUS, shown.caller.spamVerdict?.level)
            assertEquals(VerdictSource.REPUTATION_CACHE, shown.caller.spamVerdict?.source)
        }

    @Test
    fun liveLookupNeverBlocksVerifiedBusinessesOrContacts() =
        runTest {
            sources.network = NetworkLookup { NetworkVerdict(0.99f, "scam", 900, null, null) }
            sources.verifiedBusinesses += us
            assertEquals(Decision.ALLOW, engine().incoming(us).decision)
            sources.contacts[bd] = "Mum"
            val contact = engine().incoming(bd)
            assertEquals(ScreeningReason.CONTACT, contact.reason)
        }

    @Test
    fun aFailingLiveLookupIsDecidedLocally() =
        runTest {
            sources.network = NetworkLookup { throw IllegalStateException("broken") }
            val result = engine().incoming(us)
            assertEquals(Decision.ALLOW, result.decision)
            assertEquals(ScreeningReason.NO_MATCH, result.reason)
        }

    @Test
    fun liveLookupNamesShowWithTheirSource() =
        runTest {
            sources.network = NetworkLookup { NetworkVerdict(null, null, 0, "Example Bank", NameSource.VERIFIED_BUSINESS) }
            val result = engine().incoming(us)
            assertEquals("Example Bank", result.caller.displayName)
            assertEquals(NameSource.VERIFIED_BUSINESS, result.caller.nameSource)
            assertNull(result.caller.spamVerdict)
        }

    @Test
    fun blockingIoCannotHoldTheCallPastTheBudget() {
        sources.network =
            NetworkLookup {
                Thread.sleep(3_000)
                null
            }
        val engine =
            ScreeningEngine(
                sources.device(),
                sources.lists(),
                normalizer,
                DefaultDispatcherProvider,
                TimeSource.Monotonic,
                ScreeningConfig(budgetMillis = 200),
            )
        val started = System.nanoTime()
        val result = runBlocking { engine.incoming(us) }
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals(ScreeningReason.TIMEOUT, result.reason)
        assertTrue("took $tookMillis ms", tookMillis < 1_000)
    }

    @Test
    fun tcBlk012OutgoingCallsAreNeverBlocked() =
        runTest {
            sources.rule(RuleType.EXACT, us.e164)
            sources.prefs = sources.prefs.copy(blockNonContacts = true, blockForeignNumbers = true)
            val result = engine().screen(ScreeningCall(us.e164, Direction.OUTGOING))
            assertEquals(Decision.ALLOW, result.decision)
            assertEquals(ScreeningReason.OUTGOING, result.reason)
            assertEquals(false, result.showsCallerId)
            assertEquals(0, sources.rulesRead)
        }

    @Test
    fun tcBlk013EmergencyNumbersAreNeverIntercepted() =
        runTest {
            sources.rule(RuleType.REGEX, ".*")
            sources.prefs = sources.prefs.copy(blockNonContacts = true, blockHiddenNumbers = true)
            val engine = engine()
            listOf(Direction.INCOMING, Direction.OUTGOING).forEach { direction ->
                val result = engine.screen(ScreeningCall("112", direction))
                assertEquals(Decision.ALLOW, result.decision)
                assertEquals(ScreeningReason.EMERGENCY, result.reason)
                assertEquals(false, result.showsCallerId)
            }
            assertEquals(0, sources.rulesRead)
            assertEquals(0, sources.settingsRead)
        }

    @Test
    fun tcBlk013EmergencyCallsAreNotDelayedBySlowLookups() =
        runTest {
            sources.lookupDelayMillis = 5_000
            sources.network =
                NetworkLookup {
                    delay(5_000)
                    null
                }
            val result = engine().screen(ScreeningCall("911", Direction.INCOMING))
            assertEquals(ScreeningReason.EMERGENCY, result.reason)
            // Decided before any settings, contacts, list or network lookup: no virtual time passed.
            assertEquals(0L, testScheduler.currentTime)
            assertEquals(0, sources.settingsRead)
        }

    @Test
    fun unreadableHandleRings() =
        runTest {
            sources.prefs = sources.prefs.copy(blockNonContacts = true)
            val result = engine().screen(ScreeningCall("anonymous", Direction.INCOMING))
            assertEquals(Decision.ALLOW, result.decision)
            assertEquals(ScreeningReason.UNKNOWN_FORMAT, result.reason)
        }

    @Test
    fun tcBlk014StirVerdictReachesTheCallerCard() =
        runTest {
            val result = engine().incoming(us, CallerVerification.FAILED)
            assertEquals(CallerVerification.FAILED, result.caller.verification)
        }

    @Test
    fun reputationCacheAnnotatesTheCard() =
        runTest {
            sources.reputation[us] =
                CachedReputation(
                    displayName = "Acme Bank",
                    nameSource = NameSource.VERIFIED_BUSINESS,
                    spamScore = 0.45f,
                    spamCategory = "Bank",
                    reportCount = 3,
                    verifiedBusinessId = "b1",
                    logoUrl = null,
                    callReason = "Card delivery",
                    lineType = null,
                )
            val caller = engine().incoming(us).caller
            assertEquals("Acme Bank", caller.displayName)
            assertEquals(NameSource.VERIFIED_BUSINESS, caller.nameSource)
            assertEquals("b1", caller.verifiedBusinessId)
            assertEquals(RiskLevel.SUSPICIOUS, caller.spamVerdict?.level)
            assertEquals("Card delivery", caller.callReason)
        }

    @Test
    fun localLookupsRunTogether() =
        runTest {
            sources.lookupDelayMillis = 100
            val result = engine().incoming(us)
            assertEquals(ScreeningReason.NO_MATCH, result.reason)
            // Six lookups of 100 ms each: one after another they would take 600 ms. Settings, contacts and the verified
            // business check overlap with the three database reads, which run as one chain (300 ms).
            assertEquals(300, result.latencyMs)
        }

    @Test
    fun aFailedLookupOnlyCountsWhenTheDecisionNeedsIt() =
        runTest {
            sources.spamListFailure = IllegalStateException("spam list unavailable")
            sources.contacts[us] = "Ada"
            sources.rule(RuleType.EXACT, bd.e164)
            val engine = engine()
            assertEquals(ScreeningReason.CONTACT, engine.incoming(us).reason)
            assertEquals(ScreeningReason.RULE, engine.incoming(bd).reason)
            val unknown = engine.incoming(PhoneNumber("+12015550199"))
            assertEquals(Decision.ALLOW, unknown.decision)
            assertEquals(ScreeningReason.ERROR, unknown.reason)
        }
}
