package io.github.jeshiomurmu.opencaller.server.auth

import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpDeliveryException
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpService
import io.github.jeshiomurmu.opencaller.server.auth.otp.invalidCodeFormat
import io.github.jeshiomurmu.opencaller.server.auth.otp.toProblem
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.invalidNumber
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.hours

/** `POST /v1/auth/start` body. */
@Serializable
data class StartRequest(
    val e164: String,
)

/** `POST /v1/auth/start` response: how long the code is valid. */
@Serializable
data class StartResponse(
    val expiresInSeconds: Long,
)

/** `POST /v1/auth/verify` body. [integrityToken] is accepted and checked from the Play Integrity phase on. */
@Serializable
data class VerifyRequest(
    val e164: String,
    val code: String,
    val integrityToken: String? = null,
    val flavor: String? = null,
)

/** `POST /v1/auth/refresh` body. */
@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

/** Tokens returned by verify and refresh. */
@Serializable
data class TokenResponse(
    val accessToken: String,
    val accessTokenExpiresIn: Long,
    val refreshToken: String,
    val refreshTokenExpiresIn: Long,
    val tokenType: String = "Bearer",
)

/** Phone sign-in (docs/architecture.md §4.7) with the limits of docs/rules.md §7. */
class AuthService(
    private val otp: OtpService,
    private val tokens: TokenService,
    private val repository: AuthRepository,
    private val rateLimiter: RateLimiter,
) {
    /** Sends a code to [request]'s number. Counts against the per-IP limit first, then the per-number limit. */
    suspend fun start(
        request: StartRequest,
        clientIp: String,
    ): StartResponse {
        enforce(rateLimiter.hit(START_PER_IP, clientIp), TOO_MANY_CODES)
        val number = E164.parseOrNull(request.e164) ?: throw invalidNumber()
        enforce(rateLimiter.hit(START_PER_NUMBER, number.value), TOO_MANY_CODES)
        try {
            otp.start(number)
        } catch (e: OtpDeliveryException) {
            log.warn("Code for {} could not be delivered", number, e)
            throw ApiProblem(
                HttpStatusCode.ServiceUnavailable,
                "otp_delivery_failed",
                "The code could not be sent",
                "Try again in a few minutes.",
            )
        }
        return StartResponse(expiresInSeconds = otp.validFor.inWholeSeconds)
    }

    /** Checks the code and, if right, signs the user in on a new device. */
    suspend fun verify(request: VerifyRequest): TokenResponse {
        val number = E164.parseOrNull(request.e164) ?: throw invalidNumber()
        val flavor = request.flavor ?: UNKNOWN_FLAVOR
        requireValid(OtpService.isWellFormed(request.code), ::invalidCodeFormat)
        requireValid((request.integrityToken?.length ?: 0) <= MAX_INTEGRITY_TOKEN_LENGTH) {
            ApiProblem(HttpStatusCode.BadRequest, "invalid_request", "integrityToken is too long")
        }
        requireValid(flavor in FLAVORS) {
            ApiProblem(HttpStatusCode.BadRequest, "invalid_request", "flavor must be one of ${FLAVORS.joinToString()}")
        }
        otp.verify(number, request.code).toProblem()?.let { throw it }
        val refreshToken = tokens.newRefreshToken()
        val session = repository.signIn(number, flavor, TokenService.hash(refreshToken), tokens.refreshExpiry())
        return issue(session, refreshToken)
    }

    /** Rotates [request]'s refresh token. Reusing a rotated token revokes the device's tokens. */
    suspend fun refresh(request: RefreshRequest): TokenResponse {
        requireValid(request.refreshToken.length in 1..MAX_REFRESH_TOKEN_LENGTH, ::invalidRefreshToken)
        val newToken = tokens.newRefreshToken()
        val rotation = repository.rotate(TokenService.hash(request.refreshToken), TokenService.hash(newToken), tokens.refreshExpiry())
        if (rotation is Rotation.Reused) log.warn("A rotated refresh token was used again; the device's tokens were revoked")
        return (rotation as? Rotation.Rotated)?.let { issue(it.session, newToken) } ?: throw rotationProblem(rotation)
    }

    private fun rotationProblem(rotation: Rotation): ApiProblem =
        if (rotation is Rotation.Reused) {
            ApiProblem(
                HttpStatusCode.Unauthorized,
                "refresh_token_reused",
                "This sign-in was revoked",
                "Sign in again with your phone number.",
            )
        } else {
            invalidRefreshToken()
        }

    private fun issue(
        session: Session,
        refreshToken: String,
    ): TokenResponse {
        val access = tokens.accessToken(session.userId, session.deviceId)
        return TokenResponse(access.token, tokens.accessTtl.inWholeSeconds, refreshToken, tokens.refreshTtl.inWholeSeconds)
    }

    private fun invalidRefreshToken() =
        ApiProblem(
            HttpStatusCode.Unauthorized,
            "invalid_refresh_token",
            "The refresh token is not valid",
            "Sign in again with your phone number.",
        )

    /** Limits and accepted values. */
    companion object {
        /** docs/rules.md §7: OTP start 3/hour/number. */
        val START_PER_NUMBER = RateLimit("otp_start_number", maxHits = 3, window = 1.hours)

        /** docs/rules.md §7: OTP start 10/hour/IP. */
        val START_PER_IP = RateLimit("otp_start_ip", maxHits = 10, window = 1.hours)

        /** App build flavors a device may register with ([UNKNOWN_FLAVOR] when the app doesn't say). */
        val FLAVORS = setOf("foss", "play", "unknown")
        private const val UNKNOWN_FLAVOR = "unknown"
        private const val TOO_MANY_CODES = "Too many codes requested"
        private const val MAX_INTEGRITY_TOKEN_LENGTH = 4096
        private const val MAX_REFRESH_TOKEN_LENGTH = 128
        private val log = LoggerFactory.getLogger(AuthService::class.java)
    }
}
