package io.github.jeshiomurmu.opencaller.server.recording

import io.github.jeshiomurmu.opencaller.server.config.RecordingConfig
import io.github.jeshiomurmu.opencaller.server.testing.InMemoryObjectStore
import io.github.jeshiomurmu.opencaller.server.testing.RecordingPushSender
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.bearer
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.setSupporterTier
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testConfig
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours

/**
 * The voice scanner on the recording line (F-PR-13, ADR 0011): a scan session shares the line and the one-time code,
 * the recorder leaves a scan's code to the scanner (`other_kind`), and only the outcome is kept (a probability and
 * cue ids), for a day.
 */
class ScannerApiTest {
    private fun server(config: RecordingConfig = SCANNER) =
        TestServer(objectStore = InMemoryObjectStore(), config = testConfig().copy(recording = config), pushSender = RecordingPushSender())

    private suspend fun ApplicationTestBuilder.scan(token: String): HttpResponse = client.postJson("/v1/lines/scanner", "{}", bearer(token))

    private suspend fun ApplicationTestBuilder.line(token: String): RecordingLineView {
        val response = scan(token)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return testJson.decodeFromString(RecordingLineView.serializer(), response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.claim(
        code: String,
        kind: String?,
        room: String = "rec_+8809638000000_abc",
    ): HttpResponse {
        val body = if (kind == null) """{"code":"$code","room":"$room"}""" else """{"code":"$code","room":"$room","kind":"$kind"}"""
        return client.postJson("/agent/v1/recording/claim", body, bearer(AGENT_TOKEN))
    }

    private suspend fun ApplicationTestBuilder.report(
        sessionId: String,
        body: String,
    ): HttpResponse = client.postJson("/agent/v1/scanner/sessions/$sessionId/result", body, bearer(AGENT_TOKEN))

    private suspend fun ApplicationTestBuilder.session(
        token: String,
        sessionId: String,
    ): ScanSessionView =
        testJson.decodeFromString(ScanSessionView.serializer(), client.getWith("/v1/lines/scanner/$sessionId", token).bodyAsText())

    @Test
    fun tcScan001TheScannerTakesItsCodeAndOnlyTheOutcomeIsKept() =
        apiTest(server()) {
            val token = signIn(it, BD_USER).accessToken
            setSupporterTier(BD_USER, "supporter")
            val line = line(token)
            assertEquals(BD_LINE, line.number)
            assertEquals("pending", session(token, line.sessionId).state)

            // Both agents join the call: the recorder (no kind: older agents) is told the code is the scanner's.
            claim(line.code, kind = null).assertProblem(HttpStatusCode.Conflict, "other_kind")
            claim(line.code, kind = "record").assertProblem(HttpStatusCode.Conflict, "other_kind")
            assertEquals("pending", session(token, line.sessionId).state) // still waiting for the scanner
            val claimed = claim(line.code, kind = "scan")
            assertEquals(HttpStatusCode.OK, claimed.status, claimed.bodyAsText())
            val taken = testJson.decodeFromString(ClaimResponse.serializer(), claimed.bodyAsText())
            assertEquals(line.sessionId, taken.sessionId)
            assertEquals("scan", taken.kind)
            assertNull(taken.objectKey) // nothing is recorded
            assertEquals(RecordingLineService.SCAN_MAX_MINUTES, taken.maxMinutes)
            assertEquals("active", session(token, line.sessionId).state)

            // A scan isn't a recording: the recording endpoints and the recorder's result don't see it.
            client.getWith("/v1/lines/recording/${line.sessionId}", token).assertProblem(HttpStatusCode.NotFound, "session_not_found")
            client
                .postJson(
                    "/agent/v1/recording/sessions/${line.sessionId}/result",
                    """{"status":"complete","sizeBytes":0,"durationMs":1000}""",
                    bearer(AGENT_TOKEN),
                ).assertProblem(HttpStatusCode.Conflict, "session_not_active")

            // The result: a probability and cue ids, checked.
            report(
                line.sessionId,
                """{"status":"complete","spoofProbability":1.5}""",
            ).assertProblem(HttpStatusCode.BadRequest, "invalid_recording")
            report(line.sessionId, """{"status":"complete","spoofProbability":0.5,"cues":["lottery"]}""")
                .assertProblem(HttpStatusCode.BadRequest, "invalid_recording")
            report(line.sessionId, """{"status":"complete"}""").assertProblem(HttpStatusCode.BadRequest, "invalid_recording")
            val done =
                report(line.sessionId, """{"status":"complete","spoofProbability":0.83,"cues":["urgency","otp_request","urgency"]}""")
            assertEquals(HttpStatusCode.NoContent, done.status, done.bodyAsText())
            val result = session(token, line.sessionId)
            assertEquals("ended", result.state)
            assertEquals(0.83f, result.spoofProbability)
            assertEquals(listOf("otp_request", "urgency"), result.cues)
            report(line.sessionId, """{"status":"failed"}""").assertProblem(HttpStatusCode.Conflict, "session_not_active")

            // Another user doesn't see it; a day later it is gone.
            val other = signIn(it, BD_OTHER).accessToken
            client.getWith("/v1/lines/scanner/${line.sessionId}", other).assertProblem(HttpStatusCode.NotFound, "session_not_found")
            it.clock.advance(25.hours)
            assertEquals(
                1,
                it.deps.recordingRetention
                    .run()
                    .scansPurged,
            )
            client
                .getWith("/v1/lines/scanner/${line.sessionId}", signIn(it, BD_USER).accessToken)
                .assertProblem(HttpStatusCode.NotFound, "session_not_found")
        }

    @Test
    fun aRecordingCodeIsLeftToTheRecorderAndAFailedScanKeepsNoResult() =
        apiTest(server()) {
            val token = signIn(it, BD_USER).accessToken
            setSupporterTier(BD_USER, "supporter")
            val recording = client.postJson("/v1/lines/recording", "{}", bearer(token))
            val code = testJson.decodeFromString(RecordingLineView.serializer(), recording.bodyAsText()).code
            claim(code, kind = "scan").assertProblem(HttpStatusCode.Conflict, "other_kind")
            assertEquals(HttpStatusCode.OK, claim(code, kind = null).status) // the recorder, as before V10

            val line = line(token)
            claim(line.code, kind = "scan", room = "rec_other")
            assertEquals(HttpStatusCode.NoContent, report(line.sessionId, """{"status":"failed"}""").status)
            val failed = session(token, line.sessionId)
            assertEquals("failed", failed.state)
            assertNull(failed.spoofProbability)
            claim("12345678", kind = "dance").assertProblem(HttpStatusCode.BadRequest, "invalid_recording")
        }

    @Test
    fun theScannerFollowsTheSwitchTheLegalSwitchAndTheSupporterCheck() {
        apiTest(server(RECORDING_ONLY)) {
            val token = signIn(it, BD_USER).accessToken
            setSupporterTier(BD_USER, "supporter")
            scan(token).assertProblem(HttpStatusCode.Forbidden, "scanner_unavailable")
            assertEquals("false", feature("scanner"))
        }
        apiTest(server()) {
            assertEquals("true", feature("scanner"))
            scan(signIn(it, BD_USER).accessToken).assertProblem(HttpStatusCode.Forbidden, "supporter_required")
            val british = signIn(it, GB_USER).accessToken
            setSupporterTier(GB_USER, "supporter")
            scan(british).assertProblem(HttpStatusCode.Forbidden, "scanner_unavailable") // recording isn't approved there
        }
    }

    private suspend fun ApplicationTestBuilder.feature(name: String): String =
        testJson
            .parseToJsonElement(client.getWith("/v1/server").bodyAsText())
            .jsonObject
            .getValue("features")
            .jsonObject
            .getValue(name)
            .jsonPrimitive.content

    private companion object {
        const val AGENT_TOKEN = "test-recording-agent-token-of-32-chars-plus"
        const val BD_LINE = "+8809638000000"
        const val BD_USER = "+8801711000001"
        const val BD_OTHER = "+8801711000002"
        const val GB_USER = "+447911123456"
        val RECORDING_ONLY = RecordingConfig(lines = mapOf("BD" to BD_LINE, "GB" to "+442071838750"), agentToken = AGENT_TOKEN)
        val SCANNER = RECORDING_ONLY.copy(scannerEnabled = true)
    }
}
