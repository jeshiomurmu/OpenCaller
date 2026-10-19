package io.github.jeshiomurmu.opencaller.core.data

import android.os.Build
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.reports.ReportUploader
import io.github.jeshiomurmu.opencaller.core.data.reports.UploadOutcome
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ReportLabel
import io.github.jeshiomurmu.opencaller.core.model.SyncState
import io.github.jeshiomurmu.opencaller.core.network.AuthTokens
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import io.github.jeshiomurmu.opencaller.core.network.ServerConfig
import io.github.jeshiomurmu.opencaller.core.testing.FakeClock
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The report outbox and its upload (TC-REP-001: reported offline, sent when online). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class ReportUploadTest {
    private val server = MockWebServer().apply { start() }
    private val db = inMemoryDatabase()
    private val clock = FakeClock()
    private val tokens = MemoryTokens(AuthTokens("access", Long.MAX_VALUE, "refresh", Long.MAX_VALUE))
    private val network = OpenCallerNetwork(ServerConfig(server.url("/").toString()), tokens, clock)
    private val work = RecordingBackgroundWork()
    private val reports = ReportRepository(DatabaseAccess { db }, RealDispatchers, clock, work)
    private val uploader = ReportUploader(network.api, tokens, DatabaseAccess { db }, RealDispatchers)
    private val spammer = PhoneNumber("+12015550123")

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private suspend fun stateCounts() = SyncState.entries.associateWith { db.reportOutboxDao().countInState(it) }.filterValues { it > 0 }

    // TC-REP-001
    @Test
    fun aReportMadeOfflineIsSentOnceOnline() =
        runBlocking {
            reports.queue(spammer, ReportLabel.SPAM, category = "telemarketing", comment = "  Calls daily  ")
            assertEquals(listOf(0L), work.uploads)
            // Offline (nothing listens on port 1): the report stays queued with one attempt counted.
            val offline = OpenCallerNetwork(ServerConfig("http://127.0.0.1:1/"), tokens, clock)
            assertEquals(
                UploadOutcome.RetryLater(null),
                ReportUploader(offline.api, tokens, DatabaseAccess { db }, RealDispatchers).upload(),
            )
            assertEquals(mapOf(SyncState.PENDING to 1), stateCounts())
            assertEquals(
                1,
                db
                    .reportOutboxDao()
                    .inState(SyncState.PENDING, 10)
                    .single()
                    .attempts,
            )
            // Online again.
            server.enqueue(jsonResponse(202, """{"accepted":1}"""))
            assertEquals(UploadOutcome.Done(1), uploader.upload())
            assertEquals(emptyMap<SyncState, Int>(), stateCounts())
            val upload = server.takeRequest()
            assertEquals("/v1/reports", upload.url.encodedPath)
            assertEquals("Bearer access", upload.headers["Authorization"])
            assertEquals(
                """{"reports":[{"e164":"+12015550123","label":"spam","category":"telemarketing","comment":"Calls daily"}]}""",
                upload.body?.utf8(),
            )
        }

    @Test
    fun reportsWaitForSignIn() =
        runBlocking {
            tokens.tokens = null
            reports.queue(spammer, ReportLabel.NOT_SPAM, category = "scam")
            assertEquals(UploadOutcome.WaitingForSignIn, uploader.upload())
            assertEquals(0, server.requestCount)
            // A category on a not-spam report is dropped.
            assertEquals(
                null,
                db
                    .reportOutboxDao()
                    .inState(SyncState.PENDING, 10)
                    .single()
                    .category,
            )
        }

    @Test
    fun theDailyLimitWaitsForRetryAfter() =
        runBlocking {
            reports.queue(spammer, ReportLabel.SPAM)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(429)
                    .addHeader("Retry-After", "3600")
                    .build(),
            )
            assertEquals(UploadOutcome.RetryLater(3_600_000), uploader.upload())
            assertEquals(mapOf(SyncState.PENDING to 1), stateCounts())
        }

    @Test
    fun rejectedReportsDontBlockTheQueue() =
        runBlocking {
            reports.queue(spammer, ReportLabel.SPAM)
            server.enqueue(jsonResponse(400, """{"code":"invalid_phone_number"}"""))
            assertEquals(UploadOutcome.Done(0), uploader.upload())
            assertEquals(mapOf(SyncState.FAILED to 1), stateCounts())
        }

    @Test
    fun largeQueuesGoInBatchesOfFifty() =
        runBlocking {
            repeat(120) { reports.queue(PhoneNumber("+1201555${(1000 + it)}"), ReportLabel.SPAM) }
            repeat(3) { server.enqueue(jsonResponse(202, """{"accepted":50}""")) }
            assertEquals(UploadOutcome.Done(120), uploader.upload())
            assertEquals(3, server.requestCount)
            assertTrue(reports.pendingCount() == AppResult.Success(0))
        }

    @Test
    fun aReportIsGivenUpAfterTenFailedAttempts() =
        runBlocking {
            reports.queue(spammer, ReportLabel.SPAM)
            repeat(ReportUploader.MAX_ATTEMPTS) {
                server.enqueue(jsonResponse(500, """{"code":"internal_error"}"""))
                uploader.upload()
            }
            assertEquals(mapOf(SyncState.FAILED to 1), stateCounts())
        }
}
