package io.github.jeshiomurmu.opencaller.core.data

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.OpenCallerDatabase
import io.github.jeshiomurmu.opencaller.core.database.entity.NumberInfoEntity
import io.github.jeshiomurmu.opencaller.core.model.CallerVerification
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.LineType
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ReportLabel
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleTarget
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEvent
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.testing.FakeClock
import io.github.jeshiomurmu.opencaller.core.testing.Fixtures
import io.github.jeshiomurmu.opencaller.core.testing.TestDispatcherProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Repositories against an unencrypted in-memory Room database (SQLCipher's native library is Android-only). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class RepositoryTest {
    private val db =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), OpenCallerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    private val access = DatabaseAccess { db }
    private val dispatchers = TestDispatcherProvider(UnconfinedTestDispatcher())
    private val clock = FakeClock()
    private val number = PhoneNumber("+12015550123")

    @After
    fun close() {
        db.close()
    }

    @Test
    fun rulesForCallsSkipSmsOnlyAndDisabledRules() =
        runTest {
            val rules = BlockRuleRepository(access, dispatchers, clock)
            val exact = rules.add(RuleType.EXACT, number.e164, RuleAction.BLOCK).ok()
            rules.add(RuleType.PREFIX, "+1900", RuleAction.SILENCE, RuleTarget.BOTH)
            rules.add(RuleType.PREFIX, "+1976", RuleAction.BLOCK, RuleTarget.SMS)
            val off = rules.add(RuleType.COUNTRY, "NG", RuleAction.BLOCK).ok()
            rules.setEnabled(off, enabled = false)

            val forCalls = rules.enabledCallRules().ok()
            assertEquals(setOf(number.e164, "+1900"), forCalls.map { it.pattern }.toSet())
            assertEquals(
                4,
                rules
                    .observeAll()
                    .first()
                    .ok()
                    .size,
            )
            assertEquals(FakeClock.DEFAULT_START_MILLIS, forCalls.first { it.id == exact }.createdAtMillis)

            rules.add(RuleType.EXACT, number.e164, RuleAction.ALLOW)
            assertEquals(AppResult.Success(1), rules.deleteExactRules(number, setOf(RuleAction.BLOCK, RuleAction.SILENCE)))
            assertEquals(
                listOf(RuleAction.ALLOW),
                rules
                    .enabledCallRules()
                    .ok()
                    .filter { it.type == RuleType.EXACT }
                    .map { it.action },
            )
            rules.delete(off)
            assertEquals(AppResult.Success(3), rules.deleteAll())
        }

    @Test
    fun screeningEventsRoundTripAndExpire() =
        runTest {
            val events = ScreeningEventRepository(access, dispatchers, clock)
            val old = event(Decision.BLOCK, at = clock.nowMillis())
            events.record(old)
            clock.advanceBy(ScreeningEventRepository.RETENTION_MILLIS + 1)
            val id = events.record(event(Decision.SILENCE, at = clock.nowMillis())).ok()
            events.record(event(Decision.ALLOW, at = clock.nowMillis() + 1).copy(number = null, verification = CallerVerification.UNKNOWN))

            val recent = events.recent(10).ok()
            assertEquals(listOf(Decision.ALLOW, Decision.SILENCE), recent.map { it.decision })
            val saved = checkNotNull(events.find(id).ok())
            assertEquals(event(Decision.SILENCE, at = clock.nowMillis()).copy(id = id), saved)
            assertNull(recent.first().number)
            assertEquals(
                listOf(Decision.SILENCE),
                events
                    .observeBlocked(10)
                    .first()
                    .ok()
                    .map { it.decision },
            )
            assertEquals(
                2,
                events
                    .observeRecent(10)
                    .first()
                    .ok()
                    .size,
            )
            assertEquals(AppResult.Success(2), events.deleteAll())
        }

    @Test
    fun unknownReasonCodesReadAsNoMatch() =
        runTest {
            val events = ScreeningEventRepository(access, dispatchers, clock)
            val id =
                db.screeningEventDao().insert(
                    event(Decision.ALLOW, at = 1).toEntity().copy(reason = "from_the_future", stirStatus = 9),
                )
            val saved = checkNotNull(events.find(id).ok())
            assertEquals(ScreeningReason.NO_MATCH, saved.reason)
            assertEquals(CallerVerification.UNKNOWN, saved.verification)
        }

    @Test
    fun spamListAndSeedFixture() =
        runTest {
            val spam = SpamListRepository(access, dispatchers)
            val hits = SpamListSeedParser.parse(Fixtures.lines("spam_list_small.csv").asSequence())
            assertEquals(4, hits.size)
            spam.upsert(hits, listVersion = 1)
            assertEquals(AppResult.Success(4), spam.count())
            val hit = checkNotNull(spam.find(PhoneNumber("+12015550100")).ok())
            assertEquals(0.9f, hit.score)
            assertEquals("Loan", hit.category)
            assertNull(spam.find(number).getOrNull())
            assertEquals(AppResult.Success(4), spam.deleteAll())
        }

    @Test
    fun reportsGoToTheOutboxOnly() =
        runTest {
            val reports = ReportRepository(access, dispatchers, clock)
            reports.queue(number, ReportLabel.NOT_SPAM)
            assertEquals(listOf(number to ReportLabel.NOT_SPAM), reports.pending(10).getOrNull())
        }

    @Test
    fun reputationCacheHonoursExpiry() =
        runTest {
            val reputation = ReputationRepository(access, dispatchers, clock)
            assertNull(reputation.findFresh(number).getOrNull())
            db.numberInfoDao().upsert(
                NumberInfoEntity(
                    e164 = number.e164,
                    displayName = "Acme",
                    nameSource = NameSource.VERIFIED_BUSINESS,
                    spamScore = 0.1f,
                    spamCategory = null,
                    reportCount = 0,
                    isBusiness = true,
                    businessId = "b1",
                    verified = true,
                    logoUrl = null,
                    callReason = null,
                    lineType = "MOBILE",
                    fetchedAt = 0,
                    expiresAt = clock.nowMillis() + 1000,
                ),
            )
            val cached = checkNotNull(reputation.findFresh(number).ok())
            assertEquals("b1", cached.verifiedBusinessId)
            assertEquals(LineType.MOBILE, cached.lineType)
            clock.advanceBy(1000)
            assertNull(reputation.findFresh(number).getOrNull())
        }

    @Test
    fun storageFailuresBecomeStorageErrors() =
        runTest {
            val broken = DatabaseAccess { error("disk full") }
            assertEquals(AppResult.Failure(AppError.Storage), SpamListRepository(broken, dispatchers).count())
            val first = BlockRuleRepository(broken, dispatchers, clock).observeAll().first()
            assertTrue(first is AppResult.Failure)
        }

    private fun <T> AppResult<T>.ok(): T = (this as AppResult.Success).value

    private fun event(
        decision: Decision,
        at: Long,
    ) = ScreeningEvent(
        id = 0,
        number = number,
        direction = Direction.INCOMING,
        decision = decision,
        reason = ScreeningReason.RULE,
        spamScore = 0.5f,
        verification = CallerVerification.PASSED,
        latencyMs = 7,
        atMillis = at,
    )
}
