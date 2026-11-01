package io.github.jeshiomurmu.opencaller.server.profile

import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.db.ContactRequests
import io.github.jeshiomurmu.opencaller.server.db.ReviewStatus
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.db.Visibility
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.invalidNumber
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.profile.Visits.ownerOf
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.days

/**
 * Contact requests (docs/prd.md F-SO-05): a user asks for the name behind a number whose profile they can't see;
 * the owner approves or denies. The requester's answer is always the same ("sent"), whether the number has a
 * profile, a visible name, an earlier request or a denial, so requests reveal neither who is registered nor what
 * an owner decided. An approved requester sees the name in search and on the profile; the owner sees the
 * requester's number and name (asking shares them, and the app says so).
 */
class ContactRequestService(
    private val db: ServerDatabase,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    /** [requesterId] asks the owner of [raw] for their name. */
    suspend fun request(
        requesterId: UUID,
        raw: String,
    ): ContactRequestSent {
        val number = E164.parseOrNull(raw) ?: throw invalidNumber()
        enforce(rateLimiter.hit(PER_DAY, requesterId.toString()), "Too many contact requests today")
        db.query {
            val requester = activeUser(requesterId)
            requireValid(requester[Users.e164] != number.value) { invalid("You can't ask yourself.") }
            val owner = ownerOf(number.value)
            if (owner != null && hidesName(owner.id)) store(requesterId, owner.id)
        }
        return ContactRequestSent()
    }

    /** Pending requests to [userId], newest first. */
    suspend fun incoming(userId: UUID): IncomingContactRequests =
        db.query {
            activeUser(userId)
            ContactRequests
                .join(Users, JoinType.INNER, ContactRequests.requesterId, Users.id)
                .select(ContactRequests.id, ContactRequests.createdAt, Users.e164, Users.displayName)
                .where {
                    (ContactRequests.targetId eq userId) and (ContactRequests.status eq ReviewStatus.PENDING) and Users.deletedAt.isNull()
                }.orderBy(ContactRequests.createdAt, SortOrder.DESC)
                .limit(LIST_LIMIT)
                .map {
                    IncomingContactRequest(
                        id = it[ContactRequests.id],
                        requesterE164 = it[Users.e164],
                        requesterName = it[Users.displayName],
                        createdAt = it[ContactRequests.createdAt].toInstant().toString(),
                    )
                }.let(::IncomingContactRequests)
        }

    /** [userId] approves ([approve] true) or denies request [id]; 404 unless it is a pending request to them. */
    suspend fun decide(
        userId: UUID,
        id: Long,
        approve: Boolean,
    ): ContactRequestDecision {
        val status = if (approve) ReviewStatus.APPROVED else ReviewStatus.REJECTED
        val updated =
            db.query {
                activeUser(userId)
                ContactRequests.update({
                    (ContactRequests.id eq id) and (ContactRequests.targetId eq userId) and (ContactRequests.status eq ReviewStatus.PENDING)
                }) {
                    it[ContactRequests.status] = status
                    it[decidedAt] = now()
                }
            }
        if (updated == 0) throw ApiProblem(HttpStatusCode.NotFound, "not_found", "No such pending contact request")
        return ContactRequestDecision(id, if (approve) "approved" else "denied")
    }

    // Only owners whose name a signed-in user can't see get requests; for the others a request would show nothing new.
    private fun JdbcTransaction.hidesName(ownerId: UUID): Boolean =
        Users
            .select(Users.visibility)
            .where { Users.id eq ownerId }
            .single()[Users.visibility] == Visibility.NOBODY

    // One row per requester and owner: a new request reopens a denial older than [ASK_AGAIN_DAYS], else changes nothing.
    private fun JdbcTransaction.store(
        requesterId: UUID,
        ownerId: UUID,
    ) {
        val existing =
            ContactRequests
                .selectAll()
                .where { (ContactRequests.requesterId eq requesterId) and (ContactRequests.targetId eq ownerId) }
                .forUpdate()
                .singleOrNull()
        val now = now()
        when {
            existing == null -> {
                ContactRequests.insert {
                    it[ContactRequests.requesterId] = requesterId
                    it[targetId] = ownerId
                    it[createdAt] = now
                }
            }

            existing[ContactRequests.status] == ReviewStatus.REJECTED &&
                existing[ContactRequests.decidedAt]?.isBefore(now.minusDays(ASK_AGAIN_DAYS)) == true -> {
                ContactRequests.update({ ContactRequests.id eq existing[ContactRequests.id] }) {
                    it[status] = ReviewStatus.PENDING
                    it[createdAt] = now
                    it[decidedAt] = null
                }
            }
        }
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun invalid(detail: String) = ApiProblem(HttpStatusCode.BadRequest, "invalid_request", "Invalid contact request", detail)

    /** Limits. */
    companion object {
        /** Requests a user may send per day (not in docs/rules.md §7; it keeps requests from becoming spam). */
        val PER_DAY = RateLimit("contact_requests_user", maxHits = 20, window = 1.days)

        /** A denied requester may ask again after this many days. */
        const val ASK_AGAIN_DAYS = 30L

        /** Requests listed at once. */
        const val LIST_LIMIT = 200
    }
}
