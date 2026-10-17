package io.github.jeshiomurmu.opencaller.server.disputes

import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.db.Disputes
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.invalidNumber
import io.github.jeshiomurmu.opencaller.server.http.isPlainText
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.insert
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.days

/** `POST /v1/disputes` body: the number whose label is disputed and why. */
@Serializable
data class DisputeRequest(
    val e164: String,
    val reason: String,
)

/** `POST /v1/disputes` response. */
@Serializable
data class DisputeResponse(
    val id: Long,
    val status: String,
)

/** Disputes of a number's spam label (docs/prd.md F-PR-16); they wait in the review queue for a person. */
class DisputeService(
    private val db: ServerDatabase,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    /** Opens a dispute by [claimantId]. */
    suspend fun open(
        claimantId: UUID,
        request: DisputeRequest,
    ): DisputeResponse {
        val number = E164.parseOrNull(request.e164) ?: throw invalidNumber()
        val reason = request.reason.trim()
        requireValid(isPlainText(reason, MAX_REASON)) {
            ApiProblem(
                HttpStatusCode.BadRequest,
                "invalid_request",
                "Invalid dispute",
                "reason must be 1 to $MAX_REASON characters of text.",
            )
        }
        enforce(rateLimiter.hit(PER_DAY, claimantId.toString()), "Too many disputes today")
        val id =
            db.query {
                Disputes.insert {
                    it[e164] = number.value
                    it[claimantUserId] = claimantId
                    it[Disputes.reason] = reason
                    it[createdAt] = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
                }[Disputes.id]
            }
        return DisputeResponse(id, "pending")
    }

    /** Limits. */
    companion object {
        /** Longest reason. */
        const val MAX_REASON = 1000

        /** Disputes per user and day (not in docs/rules.md §7; keeps the review queue usable). */
        val PER_DAY = RateLimit("disputes_user", maxHits = 10, window = 1.days)
    }
}
