package io.github.jeshiomurmu.opencaller.server.voip

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.github.jeshiomurmu.opencaller.server.config.LiveKitConfig
import java.time.Clock
import java.time.Instant
import java.util.Date

/** A LiveKit access token and when it stops being accepted for joining. */
data class IssuedToken(
    val token: String,
    val expiresAt: Instant,
)

/**
 * LiveKit access tokens, written against LiveKit's own token code (`livekit/protocol` `auth/accesstoken.go` and
 * `auth/grants.go`): an HS256 JWT signed with the API secret, `iss` = API key, `sub` = participant identity,
 * `iat`/`nbf`/`exp`, and a `video` grant. Participants may join only their call's room, publish only their
 * microphone (no camera or screen), and subscribe; they get no room admin, recording or listing rights.
 */
class LiveKitTokens(
    private val config: LiveKitConfig,
    private val clock: Clock,
) {
    private val algorithm = Algorithm.HMAC256(config.apiSecret)

    /** A token for [identity] (a user ID) to join [room]. */
    fun issue(
        identity: String,
        room: String,
    ): IssuedToken {
        val now = clock.instant()
        val expiresAt = now.plusSeconds(config.tokenTtlSeconds)
        val grant =
            mapOf(
                "room" to room,
                "roomJoin" to true,
                "canPublish" to true,
                "canSubscribe" to true,
                "canPublishData" to true,
                "canPublishSources" to listOf(MICROPHONE),
            )
        val token =
            JWT
                .create()
                .withIssuer(config.apiKey)
                .withSubject(identity)
                .withClaim("identity", identity)
                .withIssuedAt(Date.from(now))
                .withNotBefore(Date.from(now))
                .withExpiresAt(Date.from(expiresAt))
                .withClaim("video", grant)
                .sign(algorithm)
        return IssuedToken(token, expiresAt)
    }

    private companion object {
        // LiveKit's TrackSource name for a microphone track.
        const val MICROPHONE = "microphone"
    }
}
