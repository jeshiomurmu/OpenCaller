package io.github.jeshiomurmu.opencaller.server.unlist

import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpDeliveryException
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpService
import io.github.jeshiomurmu.opencaller.server.auth.otp.invalidCodeFormat
import io.github.jeshiomurmu.opencaller.server.auth.otp.toProblem
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.UnlistRequests
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.invalidNumber
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.hours

/** `POST /v1/unlist/start` body. */
@Serializable
data class UnlistStartRequest(
    val e164: String,
)

/** `POST /v1/unlist/start` response. */
@Serializable
data class UnlistStartResponse(
    val expiresInSeconds: Long,
)

/** `POST /v1/unlist/verify` body. */
@Serializable
data class UnlistVerifyRequest(
    val e164: String,
    val code: String,
)

/** `POST /v1/unlist/verify` response: the request is verified and takes effect within [processedWithinMinutes]. */
@Serializable
data class UnlistVerifyResponse(
    val status: String,
    val processedWithinMinutes: Int,
)

/**
 * Unlisting a number (docs/prd.md F-PR-17), with or without the app: the owner proves control of the number with
 * a one-time code sent to it; the verified request is stored and the next scoring-job run processes it, after
 * which lookups and search no longer show a name for the number. Its spam score stays.
 */
class UnlistService(
    private val otp: OtpService,
    private val rateLimiter: RateLimiter,
    private val db: ServerDatabase,
    private val clock: Clock,
    private val processedWithinMinutes: Int,
) {
    /** Minutes a code stays valid. */
    val codeValidMinutes: Long get() = otp.validFor.inWholeMinutes

    /** Sends a code to [rawNumber] (written in E.164, separators allowed). Same limits as sign-in codes. */
    suspend fun start(
        rawNumber: String,
        clientIp: String,
    ): UnlistStartResponse {
        enforce(rateLimiter.hit(START_PER_IP, clientIp), TOO_MANY_CODES)
        val number = E164.parseLenient(rawNumber) ?: throw invalidNumber()
        enforce(rateLimiter.hit(START_PER_NUMBER, number.value), TOO_MANY_CODES)
        try {
            otp.start(number)
        } catch (e: OtpDeliveryException) {
            log.warn("Unlist code for {} could not be delivered", number, e)
            throw ApiProblem(HttpStatusCode.ServiceUnavailable, "otp_delivery_failed", "The code could not be sent", "Try again later.")
        }
        return UnlistStartResponse(otp.validFor.inWholeSeconds)
    }

    /** Checks the code and stores a verified unlist request (one pending request per number). */
    suspend fun verify(
        rawNumber: String,
        code: String,
    ): UnlistVerifyResponse {
        val number = E164.parseLenient(rawNumber) ?: throw invalidNumber()
        requireValid(OtpService.isWellFormed(code), ::invalidCodeFormat)
        otp.verify(number, code).toProblem()?.let { throw it }
        db.query {
            val pending =
                UnlistRequests
                    .selectAll()
                    .where { (UnlistRequests.e164 eq number.value) and UnlistRequests.processedAt.isNull() }
                    .any()
            if (!pending) {
                UnlistRequests.insert {
                    it[e164] = number.value
                    it[verifiedAt] = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
                }
            }
        }
        return UnlistVerifyResponse("verified", processedWithinMinutes)
    }

    /** Limits (the sign-in limits of docs/rules.md §7, counted separately). */
    companion object {
        /** 3 codes per hour per number. */
        val START_PER_NUMBER = RateLimit("unlist_start_number", maxHits = 3, window = 1.hours)

        /** 10 codes per hour per IP. */
        val START_PER_IP = RateLimit("unlist_start_ip", maxHits = 10, window = 1.hours)

        private const val TOO_MANY_CODES = "Too many codes requested"
        private val log = LoggerFactory.getLogger(UnlistService::class.java)
    }
}
