package io.github.jeshiomurmu.opencaller.server.assistant

import com.auth0.jwt.JWT
import io.github.jeshiomurmu.opencaller.server.config.AssistantConfig
import io.github.jeshiomurmu.opencaller.server.config.RecordingConfig
import io.github.jeshiomurmu.opencaller.server.db.AssistantCalls
import io.github.jeshiomurmu.opencaller.server.notifications.NotificationView
import io.github.jeshiomurmu.opencaller.server.testing.RecordingPushSender
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.bearer
import io.github.jeshiomurmu.opencaller.server.testing.deleteWith
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.setSupporterTier
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.github.jeshiomurmu.opencaller.server.testing.voipTestConfig
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import kotlin.time.Duration.Companion.seconds

/**
 * The call assistant at the server level (F-UT-04, TC-AST-001): a number per user from the pool, the agent's claim
 * by the dialed number with an opaque push, the data-only room token, the monthly cap and the cleanup. The server
 * never receives the caller's number.
 */
class AssistantApiTest {
    private fun server(assistant: AssistantConfig = ASSISTANT) =
        TestServer(
            config = voipTestConfig().copy(assistant = assistant, recording = RecordingConfig(agentToken = AGENT_TOKEN)),
            pushSender = RecordingPushSender(),
        )

    private suspend fun ApplicationTestBuilder.allocate(token: String): HttpResponse =
        client.postJson("/v1/lines/assistant", "{}", bearer(token))

    private suspend fun ApplicationTestBuilder.line(token: String): AssistantLineView {
        val response = allocate(token)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return testJson.decodeFromString(AssistantLineView.serializer(), response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.claim(
        number: String,
        room: String = "ast_abc",
        token: String = AGENT_TOKEN,
    ): HttpResponse = client.postJson("/agent/v1/assistant/calls", """{"number":"$number","room":"$room"}""", bearer(token))

    private suspend fun ApplicationTestBuilder.claimed(
        number: String,
        room: String = "ast_abc",
    ): AssistantClaimResponse {
        val response = claim(number, room)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return testJson.decodeFromString(AssistantClaimResponse.serializer(), response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.end(
        callId: String,
        seconds: Int,
    ): HttpResponse = client.postJson("/agent/v1/assistant/calls/$callId/end", """{"durationSeconds":$seconds}""", bearer(AGENT_TOKEN))

    private suspend fun ApplicationTestBuilder.usage(token: String): AssistantUsageView =
        testJson.decodeFromString(AssistantUsageView.serializer(), client.getWith("/v1/assistant/usage", token).bodyAsText())

    private suspend fun ApplicationTestBuilder.notifications(token: String): List<NotificationView> =
        testJson.decodeFromString(ListSerializer(NotificationView.serializer()), client.getWith("/v1/notifications", token).bodyAsText())

    @Test
    fun tcAst001TheAgentAnswersAForwardedCallAndTheAppFollowsIt() =
        apiTest(server()) {
            val token = signIn(it, BD_USER).accessToken
            setSupporterTier(BD_USER, "supporter")

            // The user gets a number of their own in their country; asking again gives the same one.
            val line = line(token)
            assertEquals(AssistantLineView(BD_NUMBER_1, "BD"), line)
            assertEquals(line, line(token))
            val got = client.getWith("/v1/lines/assistant", token)
            assertEquals(line, testJson.decodeFromString(AssistantLineView.serializer(), got.bodyAsText()))

            // A forwarded call reaches the number; the agent learns whose it is and how long it may talk.
            val call = claimed(BD_NUMBER_1.removePrefix("+")) // LiveKit may give the number without its "+"
            assertEquals(5 * 60, call.remainingSeconds)
            assertEquals(claimed(BD_NUMBER_1).callId, call.callId) // the same room claims the same call

            // The phone gets an opaque push; the item leads to the room with a token for data only.
            val notification = notifications(token).single()
            assertEquals("assistant_call", notification.kind)
            val view = assertNotNull(notification.assistantCall)
            assertEquals(call.callId, view.id)
            assertEquals("ast_abc", view.room)
            assertFalse(view.ended)
            assertEquals("ws://livekit.test:7880", view.url)
            val grant = JWT.decode(assertNotNull(view.token)).getClaim("video").asMap()
            assertEquals("ast_abc", grant["room"])
            assertEquals(false, grant["canPublish"])
            assertEquals(true, grant["canPublishData"])
            assertEquals(call.appIdentity, JWT.decode(view.token).subject)

            // The call ends after 70 s: the minutes count, the room is closed to the app.
            it.clock.advance(2.minutes)
            assertEquals(HttpStatusCode.NoContent, end(call.callId, 70).status)
            assertEquals(HttpStatusCode.NoContent, end(call.callId, 70).status) // twice changes nothing
            assertEquals(70, usage(token).usedSeconds)
            val ended =
                testJson.decodeFromString(
                    AssistantCallView.serializer(),
                    client.getWith("/v1/assistant/calls/${call.callId}", token).bodyAsText(),
                )
            assertTrue(ended.ended)
            assertNull(ended.token)

            // The server stored no phone number of the call: only the user, the room and the times.
            TestInfra.database.query {
                val row = AssistantCalls.selectAll().single()
                assertEquals(
                    setOf("id", "user_id", "room", "started_at", "ended_at", "duration_seconds"),
                    AssistantCalls.columns
                        .map { c ->
                            c.name
                        }.toSet(),
                )
                assertEquals(70, row[AssistantCalls.durationSeconds])
            }
        }

    @Test
    fun aDurationLongerThanTheCallIsClampedToTheTimeSinceTheClaim() =
        apiTest(server()) {
            val token = signIn(it, BD_USER).accessToken
            setSupporterTier(BD_USER, "supporter")
            val call = claimed(line(token).number)
            it.clock.advance(30.seconds)
            end(call.callId, 3_600)
            assertEquals(31, usage(token).usedSeconds)
        }

    @Test
    fun theMonthlyCapEndsTheAssistantAndTheNextMonthStartsAgain() =
        apiTest(server(ASSISTANT.copy(monthlyMinutes = 2))) {
            val token = signIn(it, BD_USER).accessToken
            setSupporterTier(BD_USER, "supporter")
            val number = line(token).number
            val first = claimed(number, room = "ast_1")
            assertEquals(120, first.remainingSeconds) // the cap is below the per-call maximum
            it.clock.advance(100.minutes)
            end(first.callId, 90)
            val second = claimed(number, room = "ast_2")
            assertEquals(30, second.remainingSeconds)
            it.clock.advance(1.minutes)
            end(second.callId, 30)
            claim(number, room = "ast_3").assertProblem(HttpStatusCode.TooManyRequests, "assistant_limit")
            val fresh = signIn(it, BD_USER).accessToken // the first access token has expired by now
            assertEquals(AssistantUsageView(usage(fresh).month, 120, 2, 5), usage(fresh))
            it.clock.advance((31 * 24).hours)
            assertEquals(0, usage(signIn(it, BD_USER).accessToken).usedSeconds)
            assertEquals(120, claimed(number, room = "ast_4").remainingSeconds)
        }

    @Test
    fun noCapWhenTheOperatorSetsZero() =
        apiTest(server(ASSISTANT.copy(monthlyMinutes = 0))) {
            val token = signIn(it, BD_USER).accessToken
            setSupporterTier(BD_USER, "supporter")
            val number = line(token).number
            repeat(3) { round ->
                val call = claimed(number, room = "ast_$round")
                assertEquals(300, call.remainingSeconds)
                it.clock.advance(10.minutes)
                end(call.callId, 300)
            }
            val fresh = signIn(it, BD_USER).accessToken
            assertEquals(900, usage(fresh).usedSeconds)
            assertEquals(0, usage(fresh).limitMinutes)
        }

    @Test
    fun numbersAreHandedOutPerUserAndCountryAndGoBackToThePool() =
        apiTest(server()) {
            val first = signIn(it, BD_USER).accessToken
            val second = signIn(it, BD_OTHER).accessToken
            val third = signIn(it, BD_THIRD).accessToken
            val india = signIn(it, IN_USER).accessToken
            listOf(BD_USER, BD_OTHER, BD_THIRD, IN_USER).forEach { user -> setSupporterTier(user, "supporter") }

            assertEquals(BD_NUMBER_1, line(first).number)
            assertEquals(BD_NUMBER_2, line(second).number)
            allocate(third).assertProblem(HttpStatusCode.Forbidden, "assistant_unavailable") // BD's pool is empty
            allocate(india).assertProblem(HttpStatusCode.Forbidden, "assistant_unavailable") // no number in India

            assertEquals(HttpStatusCode.NoContent, client.deleteWith("/v1/lines/assistant", first).status)
            client.getWith("/v1/lines/assistant", first).assertProblem(HttpStatusCode.NotFound, "assistant_line_not_found")
            claim(BD_NUMBER_1).assertProblem(HttpStatusCode.NotFound, "number_not_found") // nobody's number now
            assertEquals(BD_NUMBER_1, line(third).number)
        }

    @Test
    fun theAssistantIsForSupportersAndUnknownNumbersAreRefused() =
        apiTest(server()) {
            val token = signIn(it, BD_USER).accessToken
            allocate(token).assertProblem(HttpStatusCode.Forbidden, "supporter_required")
            setSupporterTier(BD_USER, "supporter")
            val number = line(token).number
            claim("+8809638999999").assertProblem(HttpStatusCode.NotFound, "number_not_found")
            claim("not a number").assertProblem(HttpStatusCode.NotFound, "number_not_found")
            claim(number, token = "wrong").assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
            setSupporterTier(BD_USER, "none") // the subscription lapsed
            claim(number).assertProblem(HttpStatusCode.Forbidden, "supporter_required")
        }

    @Test
    fun aServerWithoutNumbersHasNoAssistant() =
        apiTest(server(AssistantConfig())) {
            val token = signIn(it, BD_USER).accessToken
            setSupporterTier(BD_USER, "supporter")
            allocate(token).assertProblem(HttpStatusCode.Forbidden, "assistant_unavailable")
        }

    @Test
    fun anotherUsersCallIsNotFoundAndCallsAreDeletedADayLater() =
        apiTest(server()) {
            val token = signIn(it, BD_USER).accessToken
            setSupporterTier(BD_USER, "supporter")
            val other = signIn(it, BD_OTHER).accessToken
            val call = claimed(line(token).number)
            client.getWith("/v1/assistant/calls/${call.callId}", other).assertProblem(HttpStatusCode.NotFound, "assistant_call_not_found")
            client.getWith("/v1/assistant/calls/nonsense", token).assertProblem(HttpStatusCode.NotFound, "assistant_call_not_found")
            it.clock.advance(25.hours)
            assertEquals(
                1,
                it.deps.voipCleanup
                    .run()
                    .assistantCalls,
            )
            client
                .getWith("/v1/assistant/calls/${call.callId}", signIn(it, BD_USER).accessToken)
                .assertProblem(HttpStatusCode.NotFound, "assistant_call_not_found")
        }

    private companion object {
        const val AGENT_TOKEN = "test-recording-agent-token-of-32-chars-plus"
        const val BD_NUMBER_1 = "+8809638000001"
        const val BD_NUMBER_2 = "+8809638000002"
        const val BD_USER = "+8801711000001"
        const val BD_OTHER = "+8801711000002"
        const val BD_THIRD = "+8801711000003"
        const val IN_USER = "+918123456789"
        val ASSISTANT = AssistantConfig(numbers = mapOf(BD_NUMBER_2 to "BD", BD_NUMBER_1 to "BD"))
    }
}
