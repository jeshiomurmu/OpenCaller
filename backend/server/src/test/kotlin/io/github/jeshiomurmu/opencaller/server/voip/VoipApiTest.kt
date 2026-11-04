package io.github.jeshiomurmu.opencaller.server.voip

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier.BaseVerification
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import io.github.jeshiomurmu.opencaller.server.db.Devices
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.db.Visibility
import io.github.jeshiomurmu.opencaller.server.notifications.NotificationView
import io.github.jeshiomurmu.opencaller.server.push.PushOutcome
import io.github.jeshiomurmu.opencaller.server.testing.RecordingPushSender
import io.github.jeshiomurmu.opencaller.server.testing.TEST_LIVEKIT_SECRET
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.bearer
import io.github.jeshiomurmu.opencaller.server.testing.deleteWith
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.putJson
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.github.jeshiomurmu.opencaller.server.testing.updateProfile
import io.github.jeshiomurmu.opencaller.server.testing.voipTestConfig
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Clock
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** App-to-app calls through the API: invites, opaque pushes, room tokens, call states and who can be reached. */
class VoipApiTest {
    private val pushes = RecordingPushSender()

    private fun voipServer() = TestServer(config = voipTestConfig(), pushSender = pushes)

    private suspend fun userId(number: String): UUID =
        TestInfra.database.query { Users.select(Users.id).where { Users.e164 eq number }.single()[Users.id] }

    private suspend fun ApplicationTestBuilder.registerFcm(
        token: String,
        fcmToken: String,
    ) {
        val response = client.putJson("/v1/push", """{"type":"fcm","token":"$fcmToken"}""", token)
        assertEquals(HttpStatusCode.NoContent, response.status, response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.call(
        token: String,
        to: String,
    ): HttpResponse = client.postJson("/v1/voip/calls", """{"to":"$to"}""", bearer(token))

    private suspend fun <T> HttpResponse.body(serializer: KSerializer<T>): T {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        return testJson.decodeFromString(serializer, bodyAsText())
    }

    private fun liveKitVerifier(clock: Clock): JWTVerifier =
        (JWT.require(Algorithm.HMAC256(TEST_LIVEKIT_SECRET)).withIssuer("devkey") as BaseVerification).build(clock)

    // TC-VOIP-001 (server side): invite, opaque push, both parties in the same room, answer and hang up.
    @Test
    fun aCallRingsTheCalleeWithAnOpaquePushAndBothJoinOneRoom() =
        apiTest(voipServer()) {
            val alice = signIn(it, ALICE).accessToken
            val bob = signIn(it, BOB).accessToken
            registerFcm(bob, "bob-phone")
            updateProfile(ALICE, name = "Alice", visibility = Visibility.EVERYONE)

            val session = call(alice, BOB).body(CallSession.serializer())
            assertEquals(1, session.reachedDevices)
            assertEquals("ws://livekit.test:7880", session.url)
            val ids = listOf(userId(ALICE).toString(), userId(BOB).toString()).sorted()
            assertTrue(session.room.startsWith("${ids[0]}_${ids[1]}_"), session.room)
            assertEquals(userId(ALICE).toString(), liveKitVerifier(it.clock).verify(session.token).subject)

            // The push carries only the push item's random id: not the invite id, the room or the caller.
            val push = pushes.to("bob-phone").single()
            assertTrue(push.urgent)
            assertEquals(VoipService.RING_SECONDS, push.ttlSeconds)
            assertNotEquals(session.inviteId, push.itemId.toString())
            val note = client.getWith("/v1/notifications/${push.itemId}", bob).body(NotificationView.serializer())
            assertEquals("call_invite", note.kind)
            val invite = checkNotNull(note.call)
            assertEquals(session.inviteId, invite.inviteId)
            assertEquals("callee", invite.role)
            assertEquals("ringing", invite.state)
            assertEquals(ALICE, invite.number)
            assertEquals("Alice", invite.name)

            val roomToken =
                client
                    .postJson("/v1/voip/token", """{"inviteId":"${session.inviteId}"}""", bearer(bob))
                    .body(RoomToken.serializer())
            assertEquals(session.room, roomToken.room)
            val claims = liveKitVerifier(it.clock).verify(roomToken.token)
            assertEquals(userId(BOB).toString(), claims.subject)
            assertEquals(session.room, claims.getClaim("video").asMap()["room"])

            val accepted = client.postJson("/v1/voip/calls/${session.inviteId}/accept", "", bearer(bob)).body(InviteView.serializer())
            assertEquals("accepted", accepted.state)
            // Alice has no push endpoint; her phone still finds the update among her pending items.
            val pending = client.getWith("/v1/notifications", alice).body(ListSerializer(NotificationView.serializer()))
            assertEquals(listOf("call_update"), pending.map { n -> n.kind })
            assertEquals("accepted", pending.single().call?.state)
            assertEquals("caller", pending.single().call?.role)

            val ended = client.postJson("/v1/voip/calls/${session.inviteId}/end", "", bearer(alice)).body(InviteView.serializer())
            assertEquals("ended", ended.state)
            assertEquals(2, pushes.to("bob-phone").size) // the invite and the hang-up
            client
                .postJson("/v1/voip/token", """{"inviteId":"${session.inviteId}"}""", bearer(bob))
                .assertProblem(HttpStatusCode.Conflict, "call_state")
        }

    @Test
    fun everyCallGetsItsOwnRoomAndOnlyItsPartiesSeeIt() =
        apiTest(voipServer()) {
            val alice = signIn(it, ALICE).accessToken
            val bob = signIn(it, BOB).accessToken
            val carol = signIn(it, CAROL).accessToken
            registerFcm(bob, "bob-phone")
            val first = call(alice, BOB).body(CallSession.serializer())
            client.postJson("/v1/voip/calls/${first.inviteId}/cancel", "", bearer(alice)).body(InviteView.serializer())
            val second = call(alice, BOB).body(CallSession.serializer())
            assertNotEquals(first.room, second.room)
            assertEquals(first.room.substringBeforeLast('_'), second.room.substringBeforeLast('_'))

            client.getWith("/v1/voip/calls/${second.inviteId}", carol).assertProblem(HttpStatusCode.NotFound, "call_not_found")
            client
                .postJson("/v1/voip/token", """{"inviteId":"${second.inviteId}"}""", bearer(carol))
                .assertProblem(HttpStatusCode.NotFound, "call_not_found")
            client.getWith("/v1/voip/calls/not-a-uuid", alice).assertProblem(HttpStatusCode.NotFound, "not_found")
            // Only the callee answers or declines; only the caller cancels.
            client
                .postJson("/v1/voip/calls/${second.inviteId}/accept", "", bearer(alice))
                .assertProblem(HttpStatusCode.Conflict, "call_state")
            client
                .postJson("/v1/voip/calls/${second.inviteId}/cancel", "", bearer(bob))
                .assertProblem(HttpStatusCode.Conflict, "call_state")
            client
                .postJson(
                    "/v1/voip/calls/${second.inviteId}/explode",
                    "",
                    bearer(bob),
                ).assertProblem(HttpStatusCode.NotFound, "not_found")
            val declined = client.postJson("/v1/voip/calls/${second.inviteId}/decline", "", bearer(bob)).body(InviteView.serializer())
            assertEquals("declined", declined.state)
        }

    @Test
    fun anUnansweredCallIsMissedAfterItsRingTime() =
        apiTest(voipServer()) {
            val alice = signIn(it, ALICE).accessToken
            val bob = signIn(it, BOB).accessToken
            registerFcm(bob, "bob-phone")
            val session = call(alice, BOB).body(CallSession.serializer())
            it.clock.advance((VoipService.RING_SECONDS + 1).seconds)
            assertEquals("missed", client.getWith("/v1/voip/calls/${session.inviteId}", bob).body(InviteView.serializer()).state)
            client
                .postJson("/v1/voip/calls/${session.inviteId}/accept", "", bearer(bob))
                .assertProblem(HttpStatusCode.Conflict, "call_state")
        }

    // Nothing tells the caller whether a number has an account, has turned calls off or just has no push endpoint.
    @Test
    fun unreachableNumbersAllLookTheSame() =
        apiTest(voipServer()) {
            val alice = signIn(it, ALICE).accessToken
            val bob = signIn(it, BOB).accessToken
            signIn(it, CAROL) // signed in, no push endpoint
            registerFcm(bob, "bob-phone")
            assertEquals(true, client.getWith("/v1/voip/settings", bob).body(VoipSettings.serializer()).reachable)
            client.putJson("/v1/voip/settings", """{"reachable":false}""", bob).body(VoipSettings.serializer())

            val bodies =
                listOf(BOB, CAROL, ALICE, "+8801999999999").map { number ->
                    val problem = call(alice, number).assertProblem(HttpStatusCode.NotFound, "not_reachable")
                    problem.title to problem.detail
                }
            assertEquals(1, bodies.toSet().size, bodies.toString())
            call(alice, "12345").assertProblem(HttpStatusCode.BadRequest, "invalid_phone_number")
            assertTrue(pushes.sent.isEmpty())

            client.putJson("/v1/voip/settings", """{"reachable":true}""", bob).body(VoipSettings.serializer())
            call(alice, BOB).body(CallSession.serializer())
        }

    @Test
    fun aPushServiceThatReportsTheEndpointGoneRemovesIt() =
        apiTest(voipServer()) {
            val alice = signIn(it, ALICE).accessToken
            val bob = signIn(it, BOB).accessToken
            registerFcm(bob, "bob-phone")
            pushes.outcome = PushOutcome.GONE
            assertEquals(0, call(alice, BOB).body(CallSession.serializer()).reachedDevices)
            val bobId = userId(BOB)
            val stored = TestInfra.database.query { Devices.selectAll().where { Devices.userId eq bobId }.single() }
            assertNull(stored[Devices.pushToken])
            call(alice, BOB).assertProblem(HttpStatusCode.NotFound, "not_reachable")
        }

    @Test
    fun pushRegistrationIsValidated() =
        apiTest(voipServer()) {
            val bob = signIn(it, BOB).accessToken
            val key = "B" + "A".repeat(86) // 65 bytes, base64url
            val auth = "A".repeat(22) // 16 bytes
            val good = """{"type":"webpush","endpoint":"https://push.example.org/up/1","p256dh":"$key","auth":"$auth"}"""
            assertEquals(HttpStatusCode.NoContent, client.putJson("/v1/push", good, bob).status)
            val bad =
                listOf(
                    """{"type":"webpush","endpoint":"https://push.example.org/up/1","p256dh":"AAAA","auth":"$auth"}""",
                    """{"type":"webpush","endpoint":"ftp://push.example.org/up/1","p256dh":"$key","auth":"$auth"}""",
                    """{"type":"webpush","p256dh":"$key","auth":"$auth"}""",
                    """{"type":"fcm"}""",
                    """{"type":"apns","token":"x"}""",
                )
            bad.forEach { json -> client.putJson("/v1/push", json, bob).assertProblem(HttpStatusCode.BadRequest, "invalid_push_endpoint") }
            assertEquals(HttpStatusCode.NoContent, client.deleteWith("/v1/push", bob).status)
            val bobId = userId(BOB)
            val stored = TestInfra.database.query { Devices.selectAll().where { Devices.userId eq bobId }.single() }
            assertNull(stored[Devices.pushType])
            assertEquals(HttpStatusCode.Unauthorized, client.putJson("/v1/push", good, "not-a-token").status)
        }

    @Test
    fun withoutLiveKitAppCallsAreOffAndTheServerSaysSo() =
        apiTest(TestServer(pushSender = pushes)) {
            val alice = signIn(it, ALICE).accessToken
            call(alice, BOB).assertProblem(HttpStatusCode.ServiceUnavailable, "voip_unavailable")
            val features =
                testJson
                    .parseToJsonElement(client.getWith("/v1/server").bodyAsText())
                    .jsonObject
                    .getValue("features")
                    .jsonObject
            assertEquals("false", features.getValue("appCalls").jsonPrimitive.content)
            assertEquals("true", features.getValue("flash").jsonPrimitive.content)
        }

    @Test
    fun withoutAPushProviderNothingRings() =
        apiTest(TestServer(config = voipTestConfig())) {
            val alice = signIn(it, ALICE).accessToken
            call(alice, BOB).assertProblem(HttpStatusCode.ServiceUnavailable, "voip_unavailable")
            val features =
                testJson
                    .parseToJsonElement(client.getWith("/v1/server").bodyAsText())
                    .jsonObject
                    .getValue("features")
                    .jsonObject
            assertEquals("false", features.getValue("appCalls").jsonPrimitive.content)
            assertEquals("false", features.getValue("flash").jsonPrimitive.content)
        }

    // ADR 0007: no history of who called whom outlives a day.
    @Test
    fun cleanupDeletesInvitesAfterADayAndExpiredPushItems() =
        apiTest(voipServer()) {
            val alice = signIn(it, ALICE).accessToken
            val bob = signIn(it, BOB).accessToken
            registerFcm(bob, "bob-phone")
            call(alice, BOB).body(CallSession.serializer())
            assertEquals(VoipCleanupResult(0, 0, 0), it.deps.voipCleanup.run())
            it.clock.advance(1.hours)
            assertEquals(VoipCleanupResult(1, 0, 0), it.deps.voipCleanup.run())
            it.clock.advance(24.hours)
            assertEquals(VoipCleanupResult(0, 0, 1), it.deps.voipCleanup.run())
            val fresh = signIn(it, BOB).accessToken // the first access token expired meanwhile
            assertTrue(client.getWith("/v1/notifications", fresh).body(ListSerializer(NotificationView.serializer())).isEmpty())
        }

    private companion object {
        const val ALICE = "+8801712345678"
        const val BOB = "+8801812345678"
        const val CAROL = "+8801912345678"
    }
}
