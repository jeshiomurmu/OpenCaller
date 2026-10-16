package io.github.jeshiomurmu.opencaller.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.github.jeshiomurmu.opencaller.server.config.JwtConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/** A signed access token and when it stops being accepted. */
data class AccessToken(
    val token: String,
    val expiresAt: Instant,
)

/**
 * Access tokens: HS256 JWTs valid for [accessTtl] (15 minutes) with `sub` = user ID and `did` = device ID.
 * Refresh tokens: 256 random bits (base64url) valid for [refreshTtl] (30 days); only their SHA-256 is stored.
 */
class TokenService(
    private val config: JwtConfig,
    private val clock: Clock,
    private val random: SecureRandom,
    val accessTtl: Duration = 15.minutes,
    val refreshTtl: Duration = 30.days,
) {
    private val algorithm = Algorithm.HMAC256(config.secret)

    /** Verifies signature, issuer, audience and expiry against [clock] (java-jwt's clock hook is on BaseVerification). */
    val verifier: JWTVerifier =
        (JWT.require(algorithm).withIssuer(config.issuer).withAudience(config.audience) as JWTVerifier.BaseVerification).build(clock)

    /** A new access token for [userId] on [deviceId]. */
    fun accessToken(
        userId: UUID,
        deviceId: UUID,
    ): AccessToken {
        val now = clock.instant()
        val expiresAt = now.plus(accessTtl.toJavaDuration())
        val token =
            JWT
                .create()
                .withIssuer(config.issuer)
                .withAudience(config.audience)
                .withSubject(userId.toString())
                .withClaim(DEVICE_CLAIM, deviceId.toString())
                .withIssuedAt(now)
                .withExpiresAt(expiresAt)
                .withJWTId(UUID.randomUUID().toString())
                .sign(algorithm)
        return AccessToken(token, expiresAt)
    }

    /** A new random refresh token (what the app keeps). */
    fun newRefreshToken(): String {
        val bytes = ByteArray(REFRESH_TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** When a refresh token issued now expires. */
    fun refreshExpiry(): Instant = clock.instant().plus(refreshTtl.toJavaDuration())

    /** Claims and sizes. */
    companion object {
        /** JWT claim holding the device ID. */
        const val DEVICE_CLAIM = "did"
        private const val REFRESH_TOKEN_BYTES = 32

        /** The value stored in `refresh_tokens.token_hash` for [refreshToken]: hex SHA-256. */
        fun hash(refreshToken: String): String =
            MessageDigest.getInstance("SHA-256").digest(refreshToken.toByteArray(Charsets.UTF_8)).toHexString()
    }
}
