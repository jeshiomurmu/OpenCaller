package io.github.jeshiomurmu.opencaller.core.data

import android.os.Build
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyAlertReporter
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyCountsReporter
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyMembership
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyPreferences
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyRepository
import io.github.jeshiomurmu.opencaller.core.data.family.FamilySendOutcome
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyWork
import io.github.jeshiomurmu.opencaller.core.data.family.ScamFeedCache
import io.github.jeshiomurmu.opencaller.core.data.family.ScamFeedRepository
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.MessageMetaEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.ScreeningEventEntity
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.FamilyAlertKind
import io.github.jeshiomurmu.opencaller.core.model.FamilyInvitePreview
import io.github.jeshiomurmu.opencaller.core.model.FamilyRole
import io.github.jeshiomurmu.opencaller.core.model.FamilySharing
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import io.github.jeshiomurmu.opencaller.core.network.AuthTokens
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import io.github.jeshiomurmu.opencaller.core.network.ServerConfig
import io.github.jeshiomurmu.opencaller.core.testing.FakeClock
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory

/** Family protection on the phone (ADR 0008): joining needs consent, and only what the member shares is sent. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class FamilyDataTest {
    private val server = MockWebServer().apply { start() }
    private val db = inMemoryDatabase()
    private val clock = FakeClock(NOV_9_NOON)
    private val tokens = MemoryTokens(AuthTokens("access", Long.MAX_VALUE, "refresh", Long.MAX_VALUE))
    private val api = OpenCallerNetwork(ServerConfig(server.url("/").toString()), tokens, clock).familyApi
    private val preferences = FamilyPreferences(tempPreferences("family"))
    private val work = RecordingFamilyWork()
    private val repository = FamilyRepository(api, preferences, work)
    private val alerts = FamilyAlertReporter(api, preferences, work, clock)
    private val counts = FamilyCountsReporter(api, preferences, clock, DatabaseAccess { db }, RealDispatchers)

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private val invite = FamilyInvitePreview("ABCD2345", "Rahman family", "Rafi", 1, 5, 0, 1)

    private fun family(
        role: String = "member",
        otp: Boolean = true,
        counts: Boolean = true,
    ) = jsonResponse(
        200,
        """{"id":"g1","name":"Rahman family","adminLabel":"Rafi","memberCount":2,"maxMembers":5,"me":{"label":"Mum",""" +
            """"role":"$role","shareOtpAlerts":$otp,"shareDailyCounts":$counts,"consentedAt":"2026-11-09T12:00:00Z",""" +
            """"consentVersion":1}}""",
    )

    @Test
    fun joiningSendsTheMembersOwnConsentAndRemembersWhatTheyShare() =
        runBlocking {
            val refused = repository.join(invite, "Mum", FamilySharing(otpAlerts = true, dailyCounts = true), consented = false)
            assertEquals(AppError.Validation(FamilyRepository.CONSENT_REQUIRED), (refused as AppResult.Failure).error)
            assertEquals(0, server.requestCount)

            server.enqueue(family(counts = false))
            val group = repository.join(invite, " Mum ", FamilySharing(otpAlerts = true, dailyCounts = false), consented = true)
            assertEquals(FamilyRole.MEMBER, (group as AppResult.Success).value.myRole)
            val body = server.takeRequest().body!!.utf8()
            assertTrue(body, "\"consent\":true" in body && "\"consentVersion\":1" in body && "\"label\":\"Mum\"" in body)
            assertTrue(body, "\"shareOtpAlerts\":true" in body && "\"shareDailyCounts\":false" in body)
            assertEquals(FamilyMembership(true, false, FamilySharing(otpAlerts = true, dailyCounts = false)), preferences.current())
            assertEquals(listOf(false), work.dailyCounts)

            // Out of the group on the server: forgotten here, counts unscheduled.
            server.enqueue(jsonResponse(404, """{"code":"not_in_family"}"""))
            assertEquals(AppResult.Success(null), repository.refresh())
            assertEquals(FamilyMembership.NONE, preferences.current())
        }

    @Test
    fun aCodeThatCantBeAnInviteIsNotSent() =
        runBlocking {
            assertEquals("ABCD2345", FamilyRepository.normalizeCode(" abcd-2345 "))
            assertEquals(null, FamilyRepository.normalizeCode("ABCD0O1I")) // look-alikes are never in a code
            assertTrue(repository.preview("short") is AppResult.Failure)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun anAlertLeavesThePhoneOnlyWhenTheMemberSharesAlerts() =
        runBlocking {
            alerts.warned(FamilyAlertKind.OTP_DURING_CALL)
            assertTrue(work.alerts.isEmpty()) // not in a group

            preferences.set(FamilyMembership(true, isAdmin = true, sharing = FamilySharing.NONE))
            alerts.warned(FamilyAlertKind.OTP_DURING_CALL)
            assertTrue(work.alerts.isEmpty()) // the admin shares nothing

            preferences.set(FamilyMembership(true, isAdmin = false, sharing = FamilySharing(otpAlerts = true, dailyCounts = false)))
            alerts.warned(FamilyAlertKind.OTP_BURST)
            assertEquals(listOf(FamilyAlertKind.OTP_BURST to NOV_9_NOON), work.alerts)

            server.enqueue(jsonResponse(201, """{"id":"a1","reachedDevices":1}"""))
            assertEquals(FamilySendOutcome.DONE, alerts.send(FamilyAlertKind.OTP_BURST, NOV_9_NOON))
            val request = server.takeRequest()
            assertEquals("/v1/family/alerts", request.url.encodedPath)
            assertEquals("""{"kind":"otp_burst"}""", request.body!!.utf8()) // the kind and nothing else

            // An alert that waited more than an hour (offline) is dropped; one sent after switching off too.
            clock.advanceBy(2 * HOUR)
            assertEquals(FamilySendOutcome.DROPPED, alerts.send(FamilyAlertKind.OTP_BURST, NOV_9_NOON))
            preferences.set(FamilyMembership(true, isAdmin = false, sharing = FamilySharing.NONE))
            assertEquals(FamilySendOutcome.DROPPED, alerts.send(FamilyAlertKind.OTP_BURST, clock.nowMillis()))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun dailyCountsAreYesterdaysTwoNumbersOnly() =
        runBlocking {
            assertEquals(FamilySendOutcome.DROPPED, counts.sendYesterday(ZoneOffset.UTC))
            preferences.set(FamilyMembership(true, isAdmin = false, sharing = FamilySharing(otpAlerts = false, dailyCounts = true)))
            val yesterday = NOV_9_NOON - DAY
            listOf(Decision.BLOCK, Decision.SILENCE, Decision.ALLOW).forEach { db.screeningEventDao().insert(event(it, yesterday)) }
            db.screeningEventDao().insert(event(Decision.BLOCK, NOV_9_NOON)) // today: not yet
            listOf(MessageCategory.SPAM, MessageCategory.PHISHING, MessageCategory.PERSONAL).forEachIndexed { i, category ->
                db.messageMetaDao().upsert(message(i.toLong(), category, yesterday))
            }

            server.enqueue(MockResponse.Builder().code(204).build())
            assertEquals(FamilySendOutcome.DONE, counts.sendYesterday(ZoneOffset.UTC))
            val request = server.takeRequest()
            assertEquals("/v1/family/counts/2026-11-08", request.url.encodedPath)
            assertEquals("""{"blockedCalls":2,"spamMessages":2}""", request.body!!.utf8())

            // Sent once per day.
            assertEquals(FamilySendOutcome.DONE, counts.sendYesterday(ZoneOffset.UTC))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun scamCardsComeInTheReadersLanguageElseEnglishAndOfflineFromTheLastCopy() =
        runBlocking {
            val feed =
                ScamFeedRepository(api, ScamFeedCache(createTempDirectory("scam").toFile()), RealDispatchers)
            server.enqueue(
                jsonResponse(
                    200,
                    """{"country":"BD","cards":[{"id":"c1","language":"bn","title":"ভুয়া কুরিয়ার","body":"কোড দেবেন না।",""" +
                        """"updatedAt":"2026-11-09T10:00:00Z"},{"id":"c2","language":"en","title":"Fake courier",""" +
                        """"body":"Never share a code.","link":"https://www.bb.org.bd/","updatedAt":"2026-11-09T10:00:00Z"}]}""",
                ),
            )
            val bengali = (feed.cards("bd", "bn-BD") as AppResult.Success).value
            assertEquals(listOf("c1"), bengali.cards.map { it.id })
            assertFalse(bengali.offline)
            assertEquals("/v1/scam-feed/BD", server.takeRequest().url.encodedPath)

            repeat(2) { server.enqueue(MockResponse.Builder().code(503).build()) }
            val offline = (feed.cards("BD", "hi") as AppResult.Success).value
            assertTrue(offline.offline)
            assertEquals(listOf("c2"), offline.cards.map { it.id })
            assertEquals("https://www.bb.org.bd/", offline.cards.single().link)
            assertTrue(feed.cards("IN", "hi") is AppResult.Failure) // nothing kept for another country
        }

    private fun event(
        decision: Decision,
        at: Long,
    ) = ScreeningEventEntity(
        e164 = "+8801711000001",
        direction = Direction.INCOMING,
        decision = decision,
        reason = "test",
        spamScore = null,
        stirStatus = null,
        latencyMs = 1,
        at = at,
    )

    private fun message(
        id: Long,
        category: MessageCategory,
        at: Long,
    ) = MessageMetaEntity(id, 1, category, null, null, null, null, null, 0, 0f, at)

    private class RecordingFamilyWork : FamilyWork {
        val alerts = mutableListOf<Pair<FamilyAlertKind, Long>>()
        val dailyCounts = mutableListOf<Boolean>()

        override fun sendAlert(
            kind: FamilyAlertKind,
            atMillis: Long,
        ) {
            alerts += kind to atMillis
        }

        override fun scheduleDailyCounts(enabled: Boolean) {
            dailyCounts += enabled
        }
    }

    private companion object {
        const val NOV_9_NOON = 1_794_225_600_000L // 2026-11-09T12:00:00Z
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR
    }
}
