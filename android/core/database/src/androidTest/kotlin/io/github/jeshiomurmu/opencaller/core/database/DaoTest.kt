package io.github.jeshiomurmu.opencaller.core.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jeshiomurmu.opencaller.core.database.entity.BlockRuleEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.FamilyMemberEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.LookupBucketEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.MessageMetaEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.NumberInfoEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.OtpAppHashEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.RecordingEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.ReportOutboxEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.ScheduledSmsEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.ScreeningEventEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.SenderIdEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.SpamListEntry
import io.github.jeshiomurmu.opencaller.core.database.entity.VerificationRangeEntity
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.FamilyRole
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.ReportLabel
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleTarget
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import io.github.jeshiomurmu.opencaller.core.model.SyncState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises every DAO against an in-memory SQLCipher database. */
@RunWith(AndroidJUnit4::class)
class DaoTest {
    private val db =
        OpenCallerDatabaseFactory.create(
            context = ApplicationProvider.getApplicationContext<Context>(),
            passphrase = "test-passphrase".toByteArray(),
            name = null,
        )

    @After
    fun close() {
        db.close()
    }

    @Test
    fun spamList() =
        runTest {
            val dao = db.spamListDao()
            dao.upsertAll(listOf(SpamListEntry("+18005550100", 0.9f, "Loan", 214, 2), SpamListEntry("+18005550101", 0.5f, "Survey", 3, 1)))
            assertEquals(214, dao.find("+18005550100")?.reportCount)
            assertEquals(1, dao.deleteOlderThan(2))
            assertEquals(1, dao.count())
            assertNull(dao.find("+18005550101"))
        }

    @Test
    fun numberInfoRespectsExpiry() =
        runTest {
            val dao = db.numberInfoDao()
            dao.upsert(numberInfo(expiresAt = 100))
            assertEquals(NameSource.VERIFIED_BUSINESS, dao.findFresh("+12015550123", nowMillis = 50)?.nameSource)
            assertNull(dao.findFresh("+12015550123", nowMillis = 100))
            assertEquals(1, dao.deleteExpired(nowMillis = 100))
        }

    @Test
    fun lookupBuckets() =
        runTest {
            val dao = db.lookupBucketDao()
            dao.upsert(LookupBucketEntity(countryCode = 880, bucketId = 25856, fetchedAt = 10, etag = "v1"))
            dao.upsert(LookupBucketEntity(countryCode = 880, bucketId = 25856, fetchedAt = 20, etag = "v2"))
            assertEquals("v2", dao.find(880, 25856)?.etag)
            assertEquals(1, dao.deleteFetchedBefore(30))
            assertNull(dao.find(880, 25856))
        }

    @Test
    fun blockRules() =
        runTest {
            val dao = db.blockRuleDao()
            val id = dao.insert(rule(pattern = "+1900", createdAt = 1))
            dao.insert(rule(pattern = "+1976", createdAt = 2, enabled = false))
            assertEquals(listOf("+1976", "+1900"), dao.observeAll().first().map { it.pattern })
            assertEquals(listOf("+1900"), dao.enabledRules().map { it.pattern })

            val saved = dao.enabledRules().single()
            assertEquals(id, saved.id)
            dao.update(saved.copy(action = RuleAction.SILENCE))
            assertEquals(RuleAction.SILENCE, dao.enabledRules().single().action)
            dao.delete(saved)
            assertEquals(listOf("+1976"), dao.observeAll().first().map { it.pattern })
        }

    @Test
    fun blockRuleLookupsUsedByUndo() =
        runTest {
            val dao = db.blockRuleDao()
            val exact = dao.insert(rule(pattern = "+12015550123", createdAt = 1).copy(type = RuleType.EXACT))
            dao.insert(rule(pattern = "+1201", createdAt = 2))
            assertEquals(listOf(exact), dao.exactRulesFor("+12015550123").map { it.id })
            assertEquals("+12015550123", dao.find(exact)?.pattern)
            assertEquals(1, dao.setEnabled(exact, enabled = false))
            assertEquals(listOf("+1201"), dao.enabledRules().map { it.pattern })
            assertEquals(1, dao.deleteById(exact))
            assertNull(dao.find(exact))
            assertEquals(1, dao.deleteAll())
        }

    @Test
    fun screeningEvents() =
        runTest {
            val dao = db.screeningEventDao()
            listOf(10L, 30L, 20L).forEach { at ->
                dao.insert(
                    ScreeningEventEntity(
                        e164 = null,
                        direction = Direction.INCOMING,
                        decision = Decision.BLOCK,
                        reason = "hidden",
                        spamScore = null,
                        stirStatus = null,
                        latencyMs = 12,
                        at = at,
                    ),
                )
            }
            assertEquals(listOf(30L, 20L), dao.observeRecent(limit = 2).first().map { it.at })
            assertEquals(listOf(30L, 20L), dao.recent(limit = 2).map { it.at })
            assertEquals(1, dao.deleteOlderThan(15))
        }

    @Test
    fun blockedLogShowsOnlyBlockedAndSilencedCalls() =
        runTest {
            val dao = db.screeningEventDao()
            val decisions = listOf(Decision.ALLOW, Decision.BLOCK, Decision.SILENCE)
            val ids = decisions.mapIndexed { i, decision -> dao.insert(event(decision, at = i.toLong())) }
            assertEquals(listOf(Decision.SILENCE, Decision.BLOCK), dao.observeBlocked(limit = 10).first().map { it.decision })
            assertEquals(Decision.ALLOW, dao.find(ids.first())?.decision)
            assertEquals(3, dao.deleteAll())
            assertEquals(0, db.spamListDao().deleteAll())
        }

    private fun event(
        decision: Decision,
        at: Long,
    ) = ScreeningEventEntity(
        e164 = "+12015550123",
        direction = Direction.INCOMING,
        decision = decision,
        reason = "rule",
        spamScore = null,
        stirStatus = 1,
        latencyMs = 3,
        at = at,
    )

    @Test
    fun messageMeta() =
        runTest {
            val dao = db.messageMetaDao()
            dao.upsert(messageMeta(providerId = 1, category = MessageCategory.OTP))
            dao.upsert(messageMeta(providerId = 2, category = MessageCategory.PROMOTION))
            assertEquals(listOf(1L), dao.observeByCategory(MessageCategory.OTP).first().map { it.providerId })
            assertEquals(MessageCategory.PROMOTION, dao.find(2)?.category)
            dao.delete(2)
            assertNull(dao.find(2))
            assertEquals(1, dao.deleteThread(threadId = 1))
            assertNull(dao.find(1))
        }

    @Test
    fun registries() =
        runTest {
            db.senderIdDao().upsertAll(listOf(SenderIdEntity("IN", "AD-HDFCBK", "HDFC Bank", "BANK", "TRAI", null, 1)))
            assertEquals("HDFC Bank", db.senderIdDao().find("IN", "AD-HDFCBK")?.brandName)

            db.otpAppHashDao().upsertAll(listOf(OtpAppHashEntity("FA+9qCX9VSu", "com.example.app", "Example")))
            assertEquals("com.example.app", db.otpAppHashDao().find("FA+9qCX9VSu")?.packageName)

            val ranges = db.verificationRangeDao()
            ranges.upsertAll(listOf(VerificationRangeEntity("+44", "short"), VerificationRangeEntity("+447400", "long")))
            assertEquals("long", ranges.findLongestPrefix("+447400123456")?.provider)
            assertEquals("short", ranges.findLongestPrefix("+441212345678")?.provider)
            assertNull(ranges.findLongestPrefix("+12015550123"))
        }

    @Test
    fun reportOutbox() =
        runTest {
            val dao = db.reportOutboxDao()
            val id =
                dao.insert(
                    ReportOutboxEntity(
                        e164 = "+18005550100",
                        label = ReportLabel.SPAM,
                        category = "Loan",
                        comment = null,
                        createdAt = 1,
                        attempts = 0,
                        state = SyncState.PENDING,
                    ),
                )
            assertEquals(listOf(id), dao.inState(SyncState.PENDING, limit = 10).map { it.id })
            dao.updateState(id, SyncState.DONE, attempts = 1)
            assertEquals(emptyList<ReportOutboxEntity>(), dao.inState(SyncState.PENDING, limit = 10))
            assertEquals(1, dao.deleteInState(SyncState.DONE))
        }

    @Test
    fun recordings() =
        runTest {
            val dao = db.recordingDao()
            dao.upsert(RecordingEntity("r1", null, "/rec/r1.m4a", 1_000, null, null, 1))
            assertEquals(listOf("r1"), dao.observeAll().first().map { it.id })
            dao.delete("r1")
            assertEquals(0, dao.observeAll().first().size)
        }

    @Test
    fun scheduledSms() =
        runTest {
            val dao = db.scheduledSmsDao()
            val sms =
                ScheduledSmsEntity(
                    to = "+447400123456",
                    body = "Happy birthday",
                    subId = 1,
                    sendAt = 5,
                    workId = "w1",
                    state = SyncState.PENDING,
                )
            val id = dao.insert(sms)
            assertEquals(listOf("+447400123456"), dao.observeInState(SyncState.PENDING).first().map { it.to })
            dao.updateState(id, SyncState.DONE)
            assertEquals(0, dao.observeInState(SyncState.PENDING).first().size)
        }

    @Test
    fun familyMembers() =
        runTest {
            val dao = db.familyMemberDao()
            dao.upsertAll(
                listOf(FamilyMemberEntity("u2", "Sam", FamilyRole.MEMBER, 2), FamilyMemberEntity("u1", "Alex", FamilyRole.OWNER, 1)),
            )
            assertEquals(listOf("u1", "u2"), dao.observeAll().first().map { it.userId })
            dao.deleteAll()
            assertEquals(0, dao.observeAll().first().size)
        }

    private fun rule(
        pattern: String,
        createdAt: Long,
        enabled: Boolean = true,
    ) = BlockRuleEntity(
        type = RuleType.PREFIX,
        pattern = pattern,
        action = RuleAction.BLOCK,
        appliesTo = RuleTarget.CALLS,
        createdAt = createdAt,
        enabled = enabled,
    )

    private fun numberInfo(expiresAt: Long) =
        NumberInfoEntity(
            e164 = "+12015550123",
            displayName = "Example Clinic",
            nameSource = NameSource.VERIFIED_BUSINESS,
            spamScore = null,
            spamCategory = null,
            reportCount = 0,
            isBusiness = true,
            businessId = "b1",
            verified = true,
            logoUrl = null,
            callReason = "Appointment reminder",
            lineType = "FIXED_LINE_OR_MOBILE",
            fetchedAt = 0,
            expiresAt = expiresAt,
        )

    private fun messageMeta(
        providerId: Long,
        category: MessageCategory,
    ) = MessageMetaEntity(
        providerId = providerId,
        threadId = 1,
        category = category,
        senderName = null,
        senderSource = null,
        otpCode = null,
        otpOrigin = null,
        otpExpiresAt = null,
        riskFlags = 0,
        classifierScore = 0.5f,
        at = providerId,
    )
}
