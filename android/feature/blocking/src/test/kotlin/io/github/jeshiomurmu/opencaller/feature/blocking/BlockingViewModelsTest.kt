package io.github.jeshiomurmu.opencaller.feature.blocking

import android.os.Build
import io.github.jeshiomurmu.opencaller.core.data.sync.SpamListSyncState
import io.github.jeshiomurmu.opencaller.core.model.CallerVerification
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ReportCategory
import io.github.jeshiomurmu.opencaller.core.model.ReportLabel
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEvent
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.model.SpamThreshold
import io.github.jeshiomurmu.opencaller.core.model.SyncState
import io.github.jeshiomurmu.opencaller.core.telephony.screening.RuleProblem
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningCall
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import io.github.jeshiomurmu.opencaller.core.ui.ReportDraft
import io.github.jeshiomurmu.opencaller.core.ui.WhyReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class BlockingViewModelsTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val graph = TestGraph()
    private val number = PhoneNumber("+12015550123")

    @After
    fun close() {
        graph.close()
    }

    private fun rulesViewModel() = RulesViewModel(graph.rules, graph.validator, graph.engine, graph.normalizer, { "US" }, graph.dispatchers)

    private suspend fun ring(number: PhoneNumber) = graph.engine.screen(ScreeningCall(number.e164, Direction.INCOMING))

    @Test
    fun addingAnExactBlockOffersUndo() =
        runTest {
            val vm = rulesViewModel()
            vm.onEvent(RulesEvent.EditDraft(RuleDraft(type = RuleType.EXACT, pattern = "(201) 555-0123", action = RuleAction.BLOCK)))
            vm.onEvent(RulesEvent.SaveDraft)
            val added = awaitValue(vm.uiState) { it.message != null }
            val message = added.message as RulesMessage.Blocked
            assertEquals("+1 ••• 0123", message.masked)
            assertEquals(listOf(number.e164), awaitValue(vm.uiState) { it.rules.isNotEmpty() }.rules.map { it.pattern })
            assertEquals(Decision.BLOCK, ring(number).decision)

            // TC-BLK-015: Undo in the snackbar removes the rule; the next call rings.
            vm.onEvent(RulesEvent.UndoBlock(message.ruleId))
            awaitValue(vm.uiState) { it.rules.isEmpty() }
            assertEquals(Decision.ALLOW, ring(number).decision)
        }

    @Test
    fun editorRejectsBadPatterns() =
        runTest {
            val vm = rulesViewModel()
            vm.onEvent(RulesEvent.EditDraft(RuleDraft(type = RuleType.PREFIX, pattern = "1555")))
            vm.onEvent(RulesEvent.SaveDraft)
            assertEquals(RuleProblem.NOT_A_PREFIX, awaitValue(vm.uiState) { it.draft.problem != null }.draft.problem)

            // TC-BLK-004: a catastrophic pattern never reaches the database.
            vm.onEvent(RulesEvent.EditDraft(RuleDraft(type = RuleType.REGEX, pattern = "((a+)+)+b")))
            vm.onEvent(RulesEvent.SaveDraft)
            assertEquals(RuleProblem.SLOW_REGEX, awaitValue(vm.uiState) { it.draft.problem != null }.draft.problem)
            assertTrue(
                graph.rules
                    .enabledCallRules()
                    .orFail()
                    .isEmpty(),
            )
        }

    @Test
    fun testANumberRunsTheRealEngine() =
        runTest {
            graph.rules.add(RuleType.PREFIX, "+1555123", RuleAction.BLOCK)
            val vm = rulesViewModel()
            vm.onEvent(RulesEvent.EditTest("+1 555 123 0001"))
            vm.onEvent(RulesEvent.RunTest)
            val outcome = awaitValue(vm.uiState) { it.testOutcome != null }.testOutcome as TestOutcome.Result
            assertEquals(Decision.BLOCK, outcome.decision)
            assertEquals(ScreeningReason.RULE, outcome.reason)
            assertEquals("+1555123", outcome.rulePattern)

            vm.onEvent(RulesEvent.EditTest("12"))
            vm.onEvent(RulesEvent.RunTest)
            assertEquals(TestOutcome.NotANumber, awaitValue(vm.uiState) { it.testOutcome != null }.testOutcome)
            // Testing logs nothing.
            assertTrue(
                graph.events
                    .recent(10)
                    .orFail()
                    .isEmpty(),
            )
        }

    @Test
    fun tcBlk015UnblockFromTheLogRemovesTheRule() =
        runTest {
            graph.rules.add(RuleType.EXACT, number.e164, RuleAction.BLOCK)
            graph.events.record(event(number, ScreeningReason.RULE))
            val vm = graph.blockedLogViewModel()
            val call = awaitValue(vm.uiState) { it.calls.isNotEmpty() }.calls.single()
            assertEquals("(201) 555-0123", call.display)

            vm.onEvent(BlockedLogEvent.Unblock(call))
            assertEquals(BlockedLogMessage.Unblocked("+1 ••• 0123"), awaitValue(vm.uiState) { it.message != null }.message)
            assertTrue(
                graph.rules
                    .enabledCallRules()
                    .orFail()
                    .isEmpty(),
            )
            assertEquals(Decision.ALLOW, ring(number).decision)
        }

    @Test
    fun unblockingANumberCoveredByAPrefixAddsAnAllowRule() =
        runTest {
            graph.rules.add(RuleType.PREFIX, "+1201", RuleAction.BLOCK)
            assertEquals(Decision.BLOCK, ring(number).decision)
            UnblockNumber(graph.rules, graph.engine)(number).orFail()
            val rules = graph.rules.enabledCallRules().orFail()
            assertTrue(rules.any { it.type == RuleType.EXACT && it.action == RuleAction.ALLOW && it.pattern == number.e164 })
            assertEquals(ScreeningReason.ALLOW_RULE, ring(number).reason)
        }

    @Test
    fun settingsTogglesCountriesAndTheContactsBanner() =
        runTest {
            graph.canReadContacts = false
            val vm = graph.settingsViewModel(region = "BD")
            vm.onEvent(BlockingEvent.Update { it.copy(blockNonContacts = true, spamThreshold = SpamThreshold.HIGH) })
            val state = awaitValue(vm.uiState) { it.preferences.blockNonContacts }
            assertTrue(state.contactsMissing)
            assertEquals("BD", state.homeRegion)
            assertEquals(
                SpamThreshold.HIGH,
                graph.settings.preferences
                    .first()
                    .spamThreshold,
            )

            graph.canReadContacts = true
            vm.onEvent(BlockingEvent.Resumed)
            awaitValue(vm.uiState) { !it.contactsMissing }

            vm.onEvent(BlockingEvent.AddCountry("xx"))
            assertTrue(awaitValue(vm.uiState) { it.countryError }.countryError)
            vm.onEvent(BlockingEvent.AddCountry("us"))
            assertEquals(
                setOf("US"),
                awaitValue(vm.uiState) { it.preferences.allowedRegionCodes.isNotEmpty() }.preferences.allowedRegionCodes,
            )
            vm.onEvent(BlockingEvent.RemoveCountry("US"))
            awaitValue(vm.uiState) { it.preferences.allowedRegionCodes.isEmpty() }
        }

    @Test
    fun reportFromTheAfterCallNotificationQueuesItAndCanBlock() =
        runTest {
            val vm = graph.settingsViewModel()
            vm.showReport(number.e164)
            val shown = awaitValue(vm.uiState) { it.report != null }
            assertEquals("(201) 555-0123", shown.report?.display)
            assertFalse(shown.signedIn)
            vm.onEvent(BlockingEvent.SubmitReport(ReportDraft(ReportLabel.SPAM, ReportCategory.LOAN, " offers loans ", alsoBlock = true)))
            assertEquals(BlockingMessage.Reported("+1 ••• 0123", blocked = true), awaitValue(vm.uiState) { it.message != null }.message)
            val queued =
                graph.db
                    .reportOutboxDao()
                    .inState(SyncState.PENDING, 10)
                    .single()
            assertEquals(number.e164, queued.e164)
            assertEquals(ReportLabel.SPAM, queued.label)
            assertEquals("loan", queued.category)
            assertEquals("offers loans", queued.comment)
            assertEquals(Decision.BLOCK, ring(number).decision)
        }

    @Test
    fun notSpamReportsNeverBlockOrCarryACategory() =
        runTest {
            graph.signedIn.value = true
            val vm = graph.settingsViewModel()
            vm.showReport(number.e164)
            assertTrue(awaitValue(vm.uiState) { it.report != null && it.signedIn }.signedIn)
            vm.onEvent(BlockingEvent.SubmitReport(ReportDraft(ReportLabel.NOT_SPAM, ReportCategory.SCAM, "", alsoBlock = true)))
            assertEquals(BlockingMessage.Reported("+1 ••• 0123", blocked = false), awaitValue(vm.uiState) { it.message != null }.message)
            val queued =
                graph.db
                    .reportOutboxDao()
                    .inState(SyncState.PENDING, 10)
                    .single()
            assertEquals(ReportLabel.NOT_SPAM, queued.label)
            assertNull(queued.category)
            assertNull(queued.comment)
            assertTrue(
                graph.rules
                    .enabledCallRules()
                    .orFail()
                    .isEmpty(),
            )
        }

    @Test
    fun spamListStatusAndUpdateNow() =
        runTest {
            val vm = graph.settingsViewModel()
            assertEquals(SpamListStatus(serverConfigured = true), awaitValue(vm.uiState) { !it.loading }.spamList)
            graph.listState.value = SpamListSyncState("US", 7, 1_000)
            assertEquals(SpamListStatus(true, "US", 7, 1_000), awaitValue(vm.uiState) { it.spamList.version == 7L }.spamList)

            vm.onEvent(BlockingEvent.UpdateListNow)
            assertEquals(BlockingMessage.ListUpdateStarted, awaitValue(vm.uiState) { it.message != null }.message)
            assertEquals(1, graph.listUpdatesRequested)

            vm.onEvent(BlockingEvent.Update { it.copy(listUpdatesOnMobileData = true) })
            assertTrue(awaitValue(vm.uiState) { it.preferences.listUpdatesOnMobileData }.preferences.listUpdatesOnMobileData)
        }

    @Test
    fun withoutAServerTheListSaysSo() =
        runTest {
            graph.serverConfigured = false
            val vm = graph.settingsViewModel()
            assertFalse(awaitValue(vm.uiState) { !it.loading }.spamList.serverConfigured)
        }

    @Test
    fun blockedLogReportsAndExplains() =
        runTest {
            graph.events.record(event(number, ScreeningReason.NETWORK, spamScore = 0.9f))
            graph.events.record(event(null, ScreeningReason.HIDDEN))
            val vm = graph.blockedLogViewModel()
            val calls = awaitValue(vm.uiState) { it.calls.size == 2 }.calls
            val network = calls.single { it.number == number }
            val hidden = calls.single { it.number == null }
            assertEquals(listOf(WhyReason.LiveLookup), network.why)
            assertEquals(listOf(WhyReason.HiddenSetting), hidden.why)

            vm.onEvent(BlockedLogEvent.Explain(network))
            assertEquals(network, awaitValue(vm.uiState) { it.explaining != null }.explaining)
            vm.onEvent(BlockedLogEvent.DismissSheet)
            awaitValue(vm.uiState) { it.explaining == null }

            vm.onEvent(BlockedLogEvent.Report(hidden))
            assertNull(vm.uiState.value.reporting)
            vm.onEvent(BlockedLogEvent.Report(network))
            awaitValue(vm.uiState) { it.reporting != null }
            vm.onEvent(BlockedLogEvent.SubmitReport(ReportDraft(ReportLabel.SPAM, null, "", alsoBlock = false)))
            assertEquals(BlockedLogMessage.Reported("+1 ••• 0123", blocked = false), awaitValue(vm.uiState) { it.message != null }.message)
            assertEquals(listOf(number to ReportLabel.SPAM), graph.reports.pending(10).orFail())
        }

    private fun event(
        number: PhoneNumber?,
        reason: ScreeningReason,
        spamScore: Float? = null,
    ) = ScreeningEvent(
        0,
        number,
        Direction.INCOMING,
        Decision.BLOCK,
        reason,
        spamScore,
        CallerVerification.UNKNOWN,
        3,
        graph.clock.nowMillis(),
    )
}
