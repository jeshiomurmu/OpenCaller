package io.github.jeshiomurmu.opencaller.server.flash

import io.github.jeshiomurmu.opencaller.server.db.Visibility
import io.github.jeshiomurmu.opencaller.server.notifications.NotificationView
import io.github.jeshiomurmu.opencaller.server.testing.RecordingPushSender
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.bearer
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.putJson
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.github.jeshiomurmu.opencaller.server.testing.updateProfile
import io.github.jeshiomurmu.opencaller.server.voip.VoipCleanupResult
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** Flash messages: predefined pings, an opaque push, fetched once and then gone from the server. */
class FlashApiTest {
    private val pushes = RecordingPushSender()

    private suspend fun ApplicationTestBuilder.flash(
        token: String,
        to: String,
        kind: String = "call_me",
    ): HttpResponse = client.postJson("/v1/flash", """{"to":"$to","kind":"$kind"}""", bearer(token))

    private suspend fun ApplicationTestBuilder.registerFcm(
        token: String,
        fcmToken: String,
    ) = assertEquals(HttpStatusCode.NoContent, client.putJson("/v1/push", """{"type":"fcm","token":"$fcmToken"}""", token).status)

    @Test
    fun aFlashMessageIsFetchedOnceAndThenGoneFromTheServer() =
        apiTest(TestServer(pushSender = pushes)) {
            val alice = signIn(it, ALICE).accessToken
            val bob = signIn(it, BOB).accessToken
            registerFcm(bob, "bob-phone")
            updateProfile(ALICE, name = "Alice", visibility = Visibility.NOBODY)

            val sent = flash(alice, BOB, "running_late")
            assertEquals(HttpStatusCode.OK, sent.status, sent.bodyAsText())
            val flashSent = testJson.decodeFromString(FlashSent.serializer(), sent.bodyAsText())
            assertEquals(1, flashSent.reachedDevices)
            val push = pushes.to("bob-phone").single()
            assertEquals(false, push.urgent)
            assertEquals(24 * 60 * 60, push.ttlSeconds)

            val response = client.getWith("/v1/notifications/${push.itemId}", bob)
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val received = checkNotNull(testJson.decodeFromString(NotificationView.serializer(), response.bodyAsText()).flash)
            assertEquals("running_late", received.kind)
            assertEquals(ALICE, received.from)
            assertNull(received.name) // Alice's profile is visible to nobody

            // The phone keeps it; the server doesn't.
            client.getWith("/v1/notifications/${push.itemId}", bob).assertProblem(HttpStatusCode.NotFound, "notification_not_found")
            val pending = client.getWith("/v1/notifications", bob).bodyAsText()
            assertTrue(testJson.decodeFromString(ListSerializer(NotificationView.serializer()), pending).isEmpty())
            client.getWith("/v1/notifications/${push.itemId}", alice).assertProblem(HttpStatusCode.NotFound, "notification_not_found")
        }

    @Test
    fun onlyPredefinedKindsToReachableUsersWithinLimits() =
        apiTest(TestServer(pushSender = pushes)) {
            val alice = signIn(it, ALICE).accessToken
            val bob = signIn(it, BOB).accessToken
            registerFcm(bob, "bob-phone")
            flash(alice, BOB, "meet me at the station").assertProblem(HttpStatusCode.BadRequest, "invalid_flash")
            flash(alice, "+8801999999999").assertProblem(HttpStatusCode.NotFound, "not_reachable")
            flash(alice, ALICE).assertProblem(HttpStatusCode.NotFound, "not_reachable")
            repeat(10) { assertEquals(HttpStatusCode.OK, flash(alice, BOB, "on_my_way").status) }
            flash(alice, BOB).assertProblem(HttpStatusCode.TooManyRequests, "rate_limited")
            client.putJson("/v1/voip/settings", """{"reachable":false}""", bob)
            flash(bob, ALICE).assertProblem(HttpStatusCode.NotFound, "not_reachable")
        }

    @Test
    fun unfetchedFlashMessagesExpireAfterADay() =
        apiTest(TestServer(pushSender = pushes)) {
            val alice = signIn(it, ALICE).accessToken
            val bob = signIn(it, BOB).accessToken
            registerFcm(bob, "bob-phone")
            assertEquals(HttpStatusCode.OK, flash(alice, BOB).status)
            it.clock.advance(25.hours)
            assertEquals(VoipCleanupResult(pushItems = 1, flashMessages = 1, callInvites = 0), it.deps.voipCleanup.run())
        }

    @Test
    fun withoutAPushProviderFlashIsOff() =
        apiTest(TestServer()) {
            val alice = signIn(it, ALICE).accessToken
            flash(alice, BOB).assertProblem(HttpStatusCode.ServiceUnavailable, "flash_unavailable")
        }

    private companion object {
        const val ALICE = "+8801712345678"
        const val BOB = "+8801812345678"
    }
}
