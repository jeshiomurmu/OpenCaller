package io.github.jeshiomurmu.opencaller.server.voip

import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.config.LiveKitConfig
import io.github.jeshiomurmu.opencaller.server.db.CallInvites
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.invalidNumber
import io.github.jeshiomurmu.opencaller.server.push.PushKind
import io.github.jeshiomurmu.opencaller.server.push.PushService
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import io.github.jeshiomurmu.opencaller.server.voip.Recipients.factsFor
import io.github.jeshiomurmu.opencaller.server.voip.Recipients.reachable
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration.Companion.hours

/**
 * App-to-app calls (docs/prd.md F-CM-09, ADR 0007): the caller asks to call a number; if that number's user can be
 * reached, an invite is created, the callee's phones get a push with an opaque id, and both parties join a LiveKit
 * room named after the sorted pair of their user IDs plus a random nonce. The server only brokers the call: media
 * flows through LiveKit, and invites are deleted a day after the call.
 */
class VoipService(
    private val db: ServerDatabase,
    private val liveKit: LiveKitConfig?,
    private val push: PushService,
    private val pushEnabled: Boolean,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) {
    private val tokens = liveKit?.let { LiveKitTokens(it, clock) }

    /** True when app calls work on this server (LiveKit and a push provider are configured). */
    val enabled: Boolean get() = liveKit != null && pushEnabled

    /** Starts a call from [callerId] to [request]'s number. */
    suspend fun start(
        callerId: UUID,
        request: StartCallRequest,
    ): CallSession {
        val config = available()
        enforce(rateLimiter.hit(CALLS_PER_USER, callerId.toString()), "Too many calls")
        val number = E164.parseOrNull(request.to) ?: throw invalidNumber()
        val now = now()
        val ringUntil = now.plusSeconds(RING_SECONDS.toLong())
        val inviteId = UUID.randomUUID()
        val (callee, room) =
            db.query {
                val callee = reachable(number, callerId)
                val room = roomFor(callerId, callee)
                CallInvites.insert {
                    it[id] = inviteId
                    it[CallInvites.callerId] = callerId
                    it[calleeId] = callee
                    it[CallInvites.room] = room
                    it[state] = RINGING
                    it[createdAt] = now
                    it[CallInvites.ringUntil] = ringUntil
                }
                callee to room
            }
        val (_, reached) = push.notify(callee, PushKind.CALL_INVITE, inviteId, RING_SECONDS, urgent = true)
        val token = tokenOrFail().issue(callerId.toString(), room)
        return CallSession(inviteId.toString(), room, config.url, token.token, token.expiresAt.toString(), ringUntil.toInstant().toString(), reached)
    }

    /** Call [inviteId] as [userId] (one of its parties) sees it now. */
    suspend fun view(
        userId: UUID,
        inviteId: UUID,
    ): InviteView = db.query { viewOf(userId, inviteOf(userId, inviteId)) }

    /** A LiveKit token for [userId] to join call [inviteId]'s room (while it rings or is answered). */
    suspend fun token(
        userId: UUID,
        inviteId: UUID,
    ): RoomToken {
        val config = available()
        val row = db.query { inviteOf(userId, inviteId) }
        val state = currentState(row)
        if (state != RINGING && state != ACCEPTED) throw callOver(state)
        val token = tokenOrFail().issue(userId.toString(), row[CallInvites.room])
        return RoomToken(row[CallInvites.room], config.url, token.token, token.expiresAt.toString())
    }

    /** Applies [action] by [userId] to call [inviteId] and tells the other party. */
    suspend fun act(
        userId: UUID,
        inviteId: UUID,
        action: CallAction,
    ): InviteView {
        val (view, other) =
            db.query {
                val row = inviteOf(userId, inviteId)
                val isCaller = row[CallInvites.callerId] == userId
                val next = CallTransitions.next(currentState(row), action, isCaller) ?: throw callOver(currentState(row))
                val now = now()
                CallInvites.update({ CallInvites.id eq inviteId }) {
                    it[state] = next
                    if (next == ACCEPTED) it[answeredAt] = now else it[endedAt] = now
                }
                val updated = inviteOf(userId, inviteId)
                viewOf(userId, updated) to (if (isCaller) row[CallInvites.calleeId] else row[CallInvites.callerId])
            }
        push.notify(other, PushKind.CALL_UPDATE, inviteId, UPDATE_TTL_SECONDS, urgent = true)
        return view
    }

    /** Whether app calls and Flash messages can reach [userId]. */
    suspend fun settings(userId: UUID): VoipSettings =
        db.query { VoipSettings(Users.selectAll().where { Users.id eq userId }.first()[Users.reachable]) }

    /** Turns reachability for [userId] on or off. */
    suspend fun updateSettings(
        userId: UUID,
        settings: VoipSettings,
    ): VoipSettings {
        db.query { Users.update({ Users.id eq userId }) { it[reachable] = settings.reachable } }
        return settings
    }

    private fun available(): LiveKitConfig =
        liveKit?.takeIf { pushEnabled }
            ?: throw ApiProblem(HttpStatusCode.ServiceUnavailable, "voip_unavailable", "App calls are not available on this server")

    private fun tokenOrFail(): LiveKitTokens = checkNotNull(tokens) { "LiveKit is not configured" }

    private fun roomFor(
        a: UUID,
        b: UUID,
    ): String {
        val (first, second) = listOf(a.toString(), b.toString()).sorted()
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        return "${first}_${second}_${Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)}"
    }

    private fun JdbcTransaction.inviteOf(
        userId: UUID,
        inviteId: UUID,
    ): ResultRow =
        CallInvites
            .selectAll()
            .where { (CallInvites.id eq inviteId) and ((CallInvites.callerId eq userId) or (CallInvites.calleeId eq userId)) }
            .firstOrNull() ?: throw ApiProblem(HttpStatusCode.NotFound, "call_not_found", "No such call")

    private fun JdbcTransaction.viewOf(
        userId: UUID,
        row: ResultRow,
    ): InviteView {
        val isCaller = row[CallInvites.callerId] == userId
        val other = factsFor(if (isCaller) row[CallInvites.calleeId] else row[CallInvites.callerId])
        return InviteView(
            inviteId = row[CallInvites.id].toString(),
            state = currentState(row),
            role = if (isCaller) "caller" else "callee",
            number = other?.e164.orEmpty(),
            name = other?.name,
            // Only the callee sees the caller's clip; the caller already knows whom they called.
            videoId = other?.videoId?.takeIf { !isCaller }?.toString(),
            ringUntil = row[CallInvites.ringUntil].withOffsetSameInstant(ZoneOffset.UTC).toInstant().toString(),
        )
    }

    // A call nobody answered in time counts as missed, whether or not the row says so yet.
    private fun currentState(row: ResultRow): String {
        val state = row[CallInvites.state]
        return if (state == RINGING && row[CallInvites.ringUntil].isBefore(now())) MISSED else state
    }

    private fun callOver(state: String) =
        ApiProblem(HttpStatusCode.Conflict, "call_state", "The call can't do that now", "The call is $state.")

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    /** Call states and limits. */
    companion object {
        /** Ringing, waiting for the callee. */
        const val RINGING = "ringing"

        /** The callee answered. */
        const val ACCEPTED = "accepted"

        /** Nobody answered within [RING_SECONDS]. */
        const val MISSED = "missed"

        /** How long a call rings. */
        const val RING_SECONDS = 45
        private const val UPDATE_TTL_SECONDS = 60
        private const val NONCE_BYTES = 12
        private val CALLS_PER_USER = RateLimit("voip_calls_user", maxHits = 30, window = 1.hours)
    }
}

/** Which state an action leads to; `null` if it isn't allowed now. */
object CallTransitions {
    /** The state after [action] by the caller ([byCaller]) or the callee in state [state]. */
    fun next(
        state: String,
        action: CallAction,
        byCaller: Boolean,
    ): String? =
        when {
            state == VoipService.RINGING && action == CallAction.ACCEPT && !byCaller -> VoipService.ACCEPTED
            state == VoipService.RINGING && action == CallAction.DECLINE && !byCaller -> "declined"
            state == VoipService.RINGING && action in setOf(CallAction.CANCEL, CallAction.END) && byCaller -> "cancelled"
            state == VoipService.RINGING && action == CallAction.END -> "declined"
            state == VoipService.ACCEPTED && action == CallAction.END -> "ended"
            else -> null
        }
}
