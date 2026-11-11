package io.github.jeshiomurmu.opencaller.server.recording

import io.github.jeshiomurmu.opencaller.server.config.RecordingConfig
import io.github.jeshiomurmu.opencaller.server.db.Recordings
import io.github.jeshiomurmu.opencaller.server.notifications.NotificationView
import io.github.jeshiomurmu.opencaller.server.testing.InMemoryObjectStore
import io.github.jeshiomurmu.opencaller.server.testing.RecordingPushSender
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.bearer
import io.github.jeshiomurmu.opencaller.server.testing.deleteWith
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.putJson
import io.github.jeshiomurmu.opencaller.server.testing.setSupporterTier
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testConfig
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The recording line (F-UT-03, TC-REC-001 at the server level): allocation with the per-country legal switch and
 * the supporter check, the agent's claim by one-time code, the result and push, download, and deletion ≤ 1 h after
 * the confirmed download and ≤ 24 h in any case.
 */
class RecordingApiTest {
    private val objects = InMemoryObjectStore()

    private fun server(config: RecordingConfig = RECORDING) =
        TestServer(objectStore = objects, config = testConfig().copy(recording = config), pushSender = RecordingPushSender())

    private suspend fun ApplicationTestBuilder.allocate(token: String): HttpResponse =
        client.postJson("/v1/lines/recording", "{}", bearer(token))

    private suspend fun ApplicationTestBuilder.line(token: String): RecordingLineView {
        val response = allocate(token)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return testJson.decodeFromString(RecordingLineView.serializer(), response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.claim(
        code: String,
        room: String = "rec_+8809638000000_abc",
        token: String = AGENT_TOKEN,
    ): HttpResponse = client.postJson("/agent/v1/recording/claim", """{"code":"$code","room":"$room"}""", bearer(token))

    private suspend fun ApplicationTestBuilder.report(
        sessionId: String,
        status: String = "complete",
        durationMs: Long = 61_000,
    ): HttpResponse =
        client.postJson(
            "/agent/v1/recording/sessions/$sessionId/result",
            """{"status":"$status","sizeBytes":0,"durationMs":$durationMs}""",
            bearer(AGENT_TOKEN),
        )

    private suspend fun ApplicationTestBuilder.list(token: String): List<RecordingView> {
        val response = client.getWith("/v1/recordings", token)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return testJson.decodeFromString(ListSerializer(RecordingView.serializer()), response.bodyAsText())
    }

    // The agent records a call: claims the session, Egress writes the file, the agent reports.
    private suspend fun ApplicationTestBuilder.recordCall(token: String): RecordingView {
        val line = line(token)
        val claimed = claim(line.code)
        assertEquals(HttpStatusCode.OK, claimed.status, claimed.bodyAsText())
        val session = testJson.decodeFromString(ClaimResponse.serializer(), claimed.bodyAsText())
        objects.upload(session.objectKey, ByteArray(AUDIO_BYTES), "audio/ogg")
        assertEquals(HttpStatusCode.NoContent, report(session.sessionId).status)
        return list(token).single()
    }

    @Test
    fun tcRec001TheLineRecordsTheCallAndTheServerDeletesItAfterTheDownload() =
        apiTest(server()) {
            val token = signIn(it, BD_USER).accessToken
            setSupporterTier(BD_USER, "supporter")

            val line = line(token)
            assertEquals(BD_LINE, line.number) // the line in the user's own country
            assertEquals(8, line.code.length)
            assertTrue(line.code.all(Char::isDigit))
            assertEquals("pending", session(token, line.sessionId).state)

            // The agent reads the code from the tones and claims the session; the code works once.
            val claimed = claim(line.code)
            assertEquals(HttpStatusCode.OK, claimed.status, claimed.bodyAsText())
            val session = testJson.decodeFromString(ClaimResponse.serializer(), claimed.bodyAsText())
            assertEquals(line.sessionId, session.sessionId)
            assertEquals("recordings/${line.sessionId}.ogg", session.objectKey)
            assertEquals("active", session(token, line.sessionId).state) // the app merges now
            claim(line.code, room = "rec_other").assertProblem(HttpStatusCode.NotFound, "code_not_found")

            // Egress wrote the file; the agent reports; the app gets a push with an opaque id only.
            objects.upload(session.objectKey, ByteArray(AUDIO_BYTES), "audio/ogg")
            assertEquals(HttpStatusCode.NoContent, report(session.sessionId).status)
            val notification = notifications(token).single()
            assertEquals("recording_ready", notification.kind)
            val recording = assertNotNull(notification.recording)
            assertEquals(AUDIO_BYTES.toLong(), recording.sizeBytes) // the store's size, not the agent's
            assertEquals(61_000, recording.durationMs)
            assertEquals("off", recording.transcript)
            assertEquals(recording.id, session(token, line.sessionId).recordingId)
            assertEquals(
                it.clock.now
                    .plusSeconds(24 * 3600)
                    .toString(),
                recording.deleteBy,
            )

            val download = client.getWith("/v1/recordings/${recording.id}/download", token)
            assertEquals(HttpStatusCode.OK, download.status, download.bodyAsText())
            val link = testJson.decodeFromString(RecordingDownload.serializer(), download.bodyAsText())
            assertTrue(link.url.contains(session.objectKey))
            assertEquals("audio/ogg", link.contentType)
            assertEquals(HttpStatusCode.NoContent, client.postJson("/v1/recordings/${recording.id}/downloaded", "{}", bearer(token)).status)
            assertTrue(list(token).single().downloaded)

            // TC-REC-001: the server copy is gone within an hour of the confirmed download.
            it.clock.advance(29.minutes)
            it.deps.recordingRetention.run()
            assertTrue(objects.objects.containsKey(session.objectKey))
            it.clock.advance(1.minutes)
            val run = it.deps.recordingRetention.run()
            assertEquals(1, run.recordingsDeleted)
            assertFalse(objects.objects.containsKey(session.objectKey))
            val fresh = signIn(it, BD_USER).accessToken // the first access token has expired by now
            assertTrue(list(fresh).isEmpty())
            client.getWith("/v1/recordings/${recording.id}/download", fresh).assertProblem(HttpStatusCode.NotFound, "recording_not_found")
        }

    @Test
    fun recordingsNeverStayLongerThan24Hours() =
        apiTest(server()) {
            val token = signIn(it, BD_USER).accessToken
            setSupporterTier(BD_USER, "supporter")
            val recording = recordCall(token)
            val key = TestInfra.database.query { Recordings.selectAll().single()[Recordings.objectKey] }
            assertEquals(recording.id, TestInfra.database.query { Recordings.selectAll().single()[Recordings.id].toString() })

            it.clock.advance(23.hours)
            it.deps.recordingRetention.run()
            assertTrue(objects.objects.containsKey(key)) // never downloaded: kept until the day is (nearly) over
            it.clock.advance(50.minutes) // the last run before 24 h takes it
            assertEquals(
                1,
                it.deps.recordingRetention
                    .run()
                    .recordingsDeleted,
            )
            assertFalse(objects.objects.containsKey(key))
        }

    @Test
    fun theLegalSwitchAndTheSupporterCheckDecideWhoMayRecord() =
        apiTest(server()) {
            // Not a supporter (and not a self-hosted server).
            val bd = signIn(it, BD_USER).accessToken
            allocate(bd).assertProblem(HttpStatusCode.Forbidden, "supporter_required")
            setSupporterTier(BD_USER, "supporter")
            assertEquals(HttpStatusCode.Created, allocate(bd).status)

            // India is approved but has no line configured here; the UK isn't approved.
            val india = signIn(it, IN_USER).accessToken
            setSupporterTier(IN_USER, "gold")
            allocate(india).assertProblem(HttpStatusCode.Forbidden, "recording_unavailable")
            val uk = signIn(it, GB_USER).accessToken
            setSupporterTier(GB_USER, "supporter")
            allocate(uk).assertProblem(HttpStatusCode.Forbidden, "recording_unavailable")

            // The admin web switches Bangladesh off: no new sessions there.
            it.deps.recordingCountries.set("BD", false)
            allocate(bd).assertProblem(HttpStatusCode.Forbidden, "recording_unavailable")
            it.deps.recordingCountries.set("BD", true)
            assertEquals(HttpStatusCode.Created, allocate(bd).status)
            assertTrue(
                it.deps.recordingCountries
                    .all()
                    .first { c -> c.iso == "BD" }
                    .fromAdmin,
            )
        }

    @Test
    fun aServerWithoutALineOffersNoRecording() =
        apiTest(server(RecordingConfig())) {
            val token = signIn(it, BD_USER).accessToken
            setSupporterTier(BD_USER, "supporter")
            allocate(token).assertProblem(HttpStatusCode.Forbidden, "recording_unavailable")
            // No agent token: the agent routes don't exist.
            assertEquals(HttpStatusCode.NotFound, claim("12345678").status)
        }

    @Test
    fun selfHostedServersLetEveryoneRecord() {
        val config = testConfig().let { c -> c.copy(recording = RECORDING, billing = c.billing.copy(selfHosted = true)) }
        apiTest(TestServer(objectStore = objects, config = config)) {
            assertEquals(HttpStatusCode.Created, allocate(signIn(it, BD_USER).accessToken).status)
        }
    }

    @Test
    fun theAgentNeedsItsTokenAndCodesExpire() =
        apiTest(server()) {
            val token = signIn(it, BD_USER).accessToken
            setSupporterTier(BD_USER, "supporter")
            val line = line(token)
            claim(line.code, token = "wrong-token-of-the-right-length-000000").assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
            claim("1234").assertProblem(HttpStatusCode.NotFound, "code_not_found")
            claim("${line.code}#").assertProblem(HttpStatusCode.NotFound, "code_not_found")

            it.clock.advance(11.minutes)
            assertEquals("failed", session(token, line.sessionId).state)
            claim(line.code).assertProblem(HttpStatusCode.NotFound, "code_not_found")
            assertEquals(
                1,
                it.deps.recordingRetention
                    .run()
                    .sessionsExpired,
            )
        }

    @Test
    fun aCallGetsThreeTriesAtTheCode() =
        apiTest(server()) {
            repeat(3) { claim("00000000", room = "rec_one").assertProblem(HttpStatusCode.NotFound, "code_not_found") }
            claim("00000000", room = "rec_one").assertProblem(HttpStatusCode.TooManyRequests, "rate_limited")
        }

    @Test
    fun aFailedOrMissingFileLeavesNoRecording() =
        apiTest(server()) {
            val token = signIn(it, BD_USER).accessToken
            setSupporterTier(BD_USER, "supporter")
            val line = line(token)
            val session = testJson.decodeFromString(ClaimResponse.serializer(), claim(line.code).bodyAsText())
            // The agent says complete but Egress wrote nothing: the session fails.
            assertEquals(HttpStatusCode.NoContent, report(session.sessionId).status)
            assertEquals("failed", session(token, line.sessionId).state)
            report(session.sessionId).assertProblem(HttpStatusCode.Conflict, "session_not_active")
            assertTrue(list(token).isEmpty())
            assertTrue(notifications(token).isEmpty())
        }

    @Test
    fun anAgentThatNeverReportsLeavesNoFileBehind() =
        apiTest(server()) {
            val token = signIn(it, BD_USER).accessToken
            setSupporterTier(BD_USER, "supporter")
            val session = testJson.decodeFromString(ClaimResponse.serializer(), claim(line(token).code).bodyAsText())
            objects.upload(session.objectKey, ByteArray(AUDIO_BYTES), "audio/ogg")
            it.clock.advance(5.hours)
            assertEquals(
                1,
                it.deps.recordingRetention
                    .run()
                    .abandonedSessions,
            )
            assertFalse(objects.objects.containsKey(session.objectKey))
        }

    @Test
    fun transcriptsOnlyForUsersWhoTurnedThemOn() =
        apiTest(server()) {
            val token = signIn(it, BD_USER).accessToken
            setSupporterTier(BD_USER, "supporter")
            assertEquals(false, settings(token).transcripts)
            assertEquals(HttpStatusCode.OK, client.putJson("/v1/recordings/settings", """{"transcripts":true}""", token).status)
            assertEquals(true, settings(token).transcripts)

            val recording = recordCall(token)
            assertEquals("pending", recording.transcript)
            val next = client.postJson("/agent/v1/transcripts/next", "{}", bearer(AGENT_TOKEN))
            assertEquals(HttpStatusCode.OK, next.status, next.bodyAsText())
            val job = testJson.decodeFromString(TranscriptJob.serializer(), next.bodyAsText())
            assertEquals(recording.id, job.recordingId)
            assertEquals(HttpStatusCode.NoContent, client.postJson("/agent/v1/transcripts/next", "{}", bearer(AGENT_TOKEN)).status)

            // A confirmed download waits for the transcript, so the user gets both.
            client.postJson("/v1/recordings/${recording.id}/downloaded", "{}", bearer(token))
            it.clock.advance(40.minutes)
            assertEquals(
                0,
                it.deps.recordingRetention
                    .run()
                    .recordingsDeleted,
            )

            val done =
                client.put("/agent/v1/transcripts/${job.recordingId}") {
                    contentType(ContentType.Application.Json)
                    header(HttpHeaders.Authorization, "Bearer $AGENT_TOKEN")
                    setBody("""{"text":"Hello, this is a test."}""")
                }
            assertEquals(HttpStatusCode.NoContent, done.status, done.bodyAsText())
            val link =
                testJson.decodeFromString(
                    RecordingDownload.serializer(),
                    client.getWith("/v1/recordings/${recording.id}/download", signIn(it, BD_USER).accessToken).bodyAsText(),
                )
            assertEquals("Hello, this is a test.", link.transcript)
            assertEquals(
                1,
                it.deps.recordingRetention
                    .run()
                    .recordingsDeleted,
            )
            assertNull(TestInfra.database.query { Recordings.selectAll().firstOrNull() })
        }

    @Test
    fun usersSeeAndDeleteOnlyTheirOwnRecordings() =
        apiTest(server()) {
            val mine = signIn(it, BD_USER).accessToken
            setSupporterTier(BD_USER, "supporter")
            val recording = recordCall(mine)
            val other = signIn(it, BD_OTHER).accessToken
            assertTrue(list(other).isEmpty())
            client.getWith("/v1/recordings/${recording.id}/download", other).assertProblem(HttpStatusCode.NotFound, "recording_not_found")
            client.deleteWith("/v1/recordings/${recording.id}", other).assertProblem(HttpStatusCode.NotFound, "recording_not_found")
            client.getWith("/v1/lines/recording/${recording.id}", other).assertProblem(HttpStatusCode.NotFound, "session_not_found")

            assertEquals(HttpStatusCode.NoContent, client.deleteWith("/v1/recordings/${recording.id}", mine).status)
            assertTrue(objects.objects.isEmpty())
            assertTrue(list(mine).isEmpty())
        }

    private suspend fun ApplicationTestBuilder.session(
        token: String,
        id: String,
    ): RecordingSessionView =
        testJson.decodeFromString(RecordingSessionView.serializer(), client.getWith("/v1/lines/recording/$id", token).bodyAsText())

    private suspend fun ApplicationTestBuilder.notifications(token: String): List<NotificationView> =
        testJson.decodeFromString(ListSerializer(NotificationView.serializer()), client.getWith("/v1/notifications", token).bodyAsText())

    private suspend fun ApplicationTestBuilder.settings(token: String): RecordingSettings =
        testJson.decodeFromString(RecordingSettings.serializer(), client.getWith("/v1/recordings/settings", token).bodyAsText())

    private companion object {
        const val AGENT_TOKEN = "test-recording-agent-token-of-32-chars-plus"
        const val BD_LINE = "+8809638000000"
        const val BD_USER = "+8801711000001"
        const val BD_OTHER = "+8801711000002"
        const val IN_USER = "+918123456789"
        const val GB_USER = "+447911123456"
        const val AUDIO_BYTES = 4096
        val RECORDING = RecordingConfig(lines = mapOf("BD" to BD_LINE), agentToken = AGENT_TOKEN)
    }
}
