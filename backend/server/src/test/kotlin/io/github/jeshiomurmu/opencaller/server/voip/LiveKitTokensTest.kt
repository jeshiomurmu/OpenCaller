package io.github.jeshiomurmu.opencaller.server.voip

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier.BaseVerification
import com.auth0.jwt.algorithms.Algorithm
import io.github.jeshiomurmu.opencaller.server.config.LiveKitConfig
import io.github.jeshiomurmu.opencaller.server.testing.MutableClock
import kotlin.test.Test
import kotlin.test.assertEquals

/** LiveKit tokens have the claims LiveKit's `auth/accesstoken.go` and `auth/grants.go` read. */
class LiveKitTokensTest {
    private val config = LiveKitConfig("wss://rtc.example.org", "APIkey1", "livekit-secret-with-at-least-32-characters", 600)

    @Test
    fun tokenJoinsOnlyItsRoomAndPublishesOnlyTheMicrophone() {
        val clock = MutableClock()
        val issued = LiveKitTokens(config, clock).issue("user-1", "a_b_nonce")
        val jwt = (JWT.require(Algorithm.HMAC256(config.apiSecret)).withIssuer("APIkey1") as BaseVerification).build(clock).verify(issued.token)

        assertEquals("HS256", jwt.algorithm)
        assertEquals("user-1", jwt.subject)
        assertEquals(clock.instant().plusSeconds(600).epochSecond, jwt.expiresAtAsInstant.epochSecond)
        assertEquals(clock.instant().epochSecond, jwt.notBeforeAsInstant.epochSecond)
        val video = jwt.getClaim("video").asMap()
        assertEquals("a_b_nonce", video["room"])
        assertEquals(true, video["roomJoin"])
        assertEquals(true, video["canPublish"])
        assertEquals(true, video["canSubscribe"])
        assertEquals(listOf("microphone"), video["canPublishSources"])
        // No admin, recording or listing rights.
        assertEquals(setOf("room", "roomJoin", "canPublish", "canSubscribe", "canPublishData", "canPublishSources"), video.keys)
    }

    @Test
    fun theOrderOfStatesIsEnforced() {
        assertEquals("accepted", CallTransitions.next("ringing", CallAction.ACCEPT, byCaller = false))
        assertEquals(null, CallTransitions.next("ringing", CallAction.ACCEPT, byCaller = true))
        assertEquals("declined", CallTransitions.next("ringing", CallAction.DECLINE, byCaller = false))
        assertEquals("cancelled", CallTransitions.next("ringing", CallAction.CANCEL, byCaller = true))
        assertEquals("cancelled", CallTransitions.next("ringing", CallAction.END, byCaller = true))
        assertEquals("declined", CallTransitions.next("ringing", CallAction.END, byCaller = false))
        assertEquals("ended", CallTransitions.next("accepted", CallAction.END, byCaller = false))
        assertEquals(null, CallTransitions.next("accepted", CallAction.ACCEPT, byCaller = false))
        assertEquals(null, CallTransitions.next("ended", CallAction.END, byCaller = true))
        assertEquals(null, CallTransitions.next("missed", CallAction.ACCEPT, byCaller = false))
    }
}
