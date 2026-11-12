package io.github.jeshiomurmu.opencaller.server.assistant

import io.github.jeshiomurmu.opencaller.server.config.AssistantConfig
import io.github.jeshiomurmu.opencaller.server.config.LiveKitConfig
import io.github.jeshiomurmu.opencaller.server.db.AssistantCalls
import io.github.jeshiomurmu.opencaller.server.db.AssistantNumbers
import io.github.jeshiomurmu.opencaller.server.db.AssistantUsage
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.profile.Entitlements
import io.github.jeshiomurmu.opencaller.server.profile.supporterRequired
import io.github.jeshiomurmu.opencaller.server.push.PushKind
import io.github.jeshiomurmu.opencaller.server.push.PushService
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import io.github.jeshiomurmu.opencaller.server.voip.LiveKitTokens
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

/**
 * Calls the assistant answers (ADR 0010). The agent claims a call by the number the caller dialed and learns whose
 * it is and how long it may talk; the user's phone gets a push with only the call's id and joins the room for the
 * transcript. The server keeps the user, the room and the duration: never the caller's number or what was said.
 */
@Suppress("LongParameterList")
class AssistantCallService(
    private val db: ServerDatabase,
    private val config: AssistantConfig,
    private val liveKit: LiveKitConfig?,
    private val entitlements: Entitlements,
    private val push: PushService,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    private val tokens = liveKit?.let { LiveKitTokens(it, clock) }

    /**
     * The agent answered a call to [AssistantClaimRequest.number] in [AssistantClaimRequest.room]: 404
     * `number_not_found` if no user has that number, 429 `assistant_limit` once the month's minutes are used up.
     * Claiming the same room again returns the same call.
     */
    suspend fun claim(request: AssistantClaimRequest): AssistantClaimResponse {
        enforce(rateLimiter.hit(CLAIMS, "all"), "Too many assistant calls")
        val number = "+" + request.number.removePrefix("+")
        val room = request.room.trim()
        requireValid(E164_NUMBER.matches(number)) { assistantNumberNotFound() }
        requireValid(room.isNotEmpty() && room.length <= MAX_ROOM_LENGTH) { assistantNumberNotFound() }
        val userId =
            db.query {
                AssistantNumbers
                    .join(Users, JoinType.INNER, AssistantNumbers.userId, Users.id)
                    .select(Users.id)
                    .where { (AssistantNumbers.e164 eq number) and (AssistantNumbers.enabled eq true) and Users.deletedAt.isNull() }
                    .singleOrNull()
                    ?.get(Users.id)
            } ?: throw assistantNumberNotFound()
        requireValid(entitlements.everyone || entitlements.isSupporter(userId), ::supporterRequired)
        val (callId, remaining, isNew) =
            db.query {
                val existing =
                    AssistantCalls
                        .selectAll()
                        .where { (AssistantCalls.userId eq userId) and (AssistantCalls.room eq room) and AssistantCalls.endedAt.isNull() }
                        .singleOrNull()
                val remaining = remainingSeconds(usedSeconds(userId, monthOf(clock)))
                if (remaining <= 0) throw assistantLimit()
                if (existing != null) {
                    Triple(existing[AssistantCalls.id], remaining, false)
                } else {
                    val id = UUID.randomUUID()
                    AssistantCalls.insert {
                        it[AssistantCalls.id] = id
                        it[AssistantCalls.userId] = userId
                        it[AssistantCalls.room] = room
                        it[startedAt] = now()
                    }
                    Triple(id, remaining, true)
                }
            }
        if (isNew) push.notify(userId, PushKind.ASSISTANT_CALL, callId, config.maxCallMinutes * SECONDS_PER_MINUTE, urgent = true)
        return AssistantClaimResponse(callId.toString(), userId.toString(), remaining)
    }

    /**
     * The agent's call [callId] ended after [AssistantEndRequest.durationSeconds] (clamped to the time since the claim);
     * the seconds count towards the month the call started in. Ending a call twice changes nothing.
     */
    suspend fun end(
        callId: UUID,
        request: AssistantEndRequest,
    ) {
        val now = now()
        db.query {
            val row =
                AssistantCalls
                    .selectAll()
                    .where { AssistantCalls.id eq callId }
                    .forUpdate()
                    .singleOrNull() ?: throw assistantCallNotFound()
            if (row[AssistantCalls.endedAt] != null) return@query
            val started = row[AssistantCalls.startedAt]
            val elapsed = Duration.between(started, now).seconds.toInt() + 1
            val seconds = request.durationSeconds.coerceIn(0, elapsed.coerceAtLeast(0))
            AssistantCalls.update({ AssistantCalls.id eq callId }) {
                it[endedAt] = now
                it[durationSeconds] = seconds
            }
            AssistantUsage.upsert(onUpdate = { it[AssistantUsage.seconds] = AssistantUsage.seconds + seconds }) {
                it[userId] = row[AssistantCalls.userId]
                it[month] = started.toLocalDate().withDayOfMonth(1)
                it[AssistantUsage.seconds] = seconds
            }
        }
    }

    /** [userId]'s call [callId], with a data-only LiveKit token while it lasts; `null` if it is gone. */
    suspend fun view(
        userId: UUID,
        callId: UUID,
    ): AssistantCallView? {
        val row =
            db.query {
                AssistantCalls
                    .selectAll()
                    .where { (AssistantCalls.id eq callId) and (AssistantCalls.userId eq userId) }
                    .singleOrNull()
            } ?: return null
        return viewOf(userId, row)
    }

    private fun viewOf(
        userId: UUID,
        row: ResultRow,
    ): AssistantCallView {
        val ended = row[AssistantCalls.endedAt] != null
        val room = row[AssistantCalls.room]
        val issued = if (ended) null else tokens?.issue(userId.toString(), room, publishMedia = false)
        return AssistantCallView(
            id = row[AssistantCalls.id].toString(),
            startedAt = row[AssistantCalls.startedAt].toInstant().toString(),
            ended = ended,
            room = room,
            url = issued?.let { liveKit?.url },
            token = issued?.token,
            expiresAt = issued?.expiresAt?.toString(),
        )
    }

    // Seconds the next call may last: the per-call maximum, within what is left of the month's cap.
    private fun remainingSeconds(used: Int): Int {
        val perCall = config.maxCallMinutes * SECONDS_PER_MINUTE
        if (config.monthlyMinutes == 0) return perCall
        return minOf(perCall, config.monthlyMinutes * SECONDS_PER_MINUTE - used)
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private companion object {
        const val SECONDS_PER_MINUTE = 60
        const val MAX_ROOM_LENGTH = 128
        val E164_NUMBER = Regex("\\+[1-9][0-9]{6,14}")
        val CLAIMS = RateLimit("assistant_claims", maxHits = 600, window = 10.minutes)
    }
}
