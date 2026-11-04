package io.github.jeshiomurmu.opencaller.server.flash

import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.db.FlashMessages
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.invalidNumber
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.push.PushKind
import io.github.jeshiomurmu.opencaller.server.push.PushService
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import io.github.jeshiomurmu.opencaller.server.voip.Recipients.factsFor
import io.github.jeshiomurmu.opencaller.server.voip.Recipients.reachable
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.hours

/** `POST /v1/flash`: send the predefined ping [kind] to [to] (E.164). */
@Serializable
data class SendFlashRequest(
    val to: String,
    val kind: String,
)

/** A sent Flash message; [reachedDevices] is how many of the recipient's phones the push reached. */
@Serializable
data class FlashSent(
    val id: String,
    val reachedDevices: Int,
)

/** A received Flash message, from [from] (with [name] as their profile allows). */
@Serializable
data class FlashView(
    val id: String,
    val kind: String,
    val from: String,
    val name: String? = null,
    val sentAt: String,
)

/**
 * Flash messages (docs/prd.md F-CM-10): one-tap predefined pings between OpenCaller users, no free text and no
 * location. A message waits on the server only until the recipient's phone fetches it (then it is deleted, and the
 * phone keeps it) or for a day.
 */
class FlashService(
    private val db: ServerDatabase,
    private val push: PushService,
    private val pushEnabled: Boolean,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    /** True when a push provider is configured. */
    val enabled: Boolean get() = pushEnabled

    /** Sends [request] from [senderId]. */
    suspend fun send(
        senderId: UUID,
        request: SendFlashRequest,
    ): FlashSent {
        requireValid(pushEnabled) {
            ApiProblem(HttpStatusCode.ServiceUnavailable, "flash_unavailable", "Flash messages are not available on this server")
        }
        requireValid(request.kind in KINDS) {
            ApiProblem(HttpStatusCode.BadRequest, "invalid_flash", "Unknown Flash message", "kind must be one of ${KINDS.joinToString()}.")
        }
        val number = E164.parseOrNull(request.to) ?: throw invalidNumber()
        enforce(rateLimiter.hit(PER_SENDER, senderId.toString()), "Too many Flash messages")
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        val flashId = UUID.randomUUID()
        val recipient = db.query { reachable(number, senderId) }
        enforce(rateLimiter.hit(PER_PAIR, "$senderId:$recipient"), "Too many Flash messages to this person")
        db.query {
            FlashMessages.insert {
                it[id] = flashId
                it[FlashMessages.senderId] = senderId
                it[recipientId] = recipient
                it[kind] = request.kind
                it[createdAt] = now
                it[expiresAt] = now.plusSeconds(TTL_SECONDS.toLong())
            }
        }
        val (_, reached) = push.notify(recipient, PushKind.FLASH, flashId, TTL_SECONDS, urgent = false)
        return FlashSent(flashId.toString(), reached)
    }

    /** Hands Flash message [flashId] to [userId] (its recipient) and deletes it from the server; `null` if gone. */
    suspend fun receive(
        userId: UUID,
        flashId: UUID,
    ): FlashView? =
        db.query {
            val row =
                FlashMessages
                    .selectAll()
                    .where { (FlashMessages.id eq flashId) and (FlashMessages.recipientId eq userId) }
                    .firstOrNull() ?: return@query null
            val sender = factsFor(row[FlashMessages.senderId])
            FlashMessages.deleteWhere { FlashMessages.id eq flashId }
            FlashView(
                id = flashId.toString(),
                kind = row[FlashMessages.kind],
                from = sender?.e164.orEmpty(),
                name = sender?.name,
                sentAt = row[FlashMessages.createdAt].toInstant().toString(),
            )
        }

    /** Kinds and limits. */
    companion object {
        /** The predefined pings; the app shows each in the reader's language. */
        val KINDS = setOf("call_me", "on_my_way", "running_late", "cant_talk", "arrived", "call_you_back")
        private const val TTL_SECONDS = 24 * 60 * 60
        private val PER_SENDER = RateLimit("flash_sender", maxHits = 60, window = 1.hours)
        private val PER_PAIR = RateLimit("flash_pair", maxHits = 10, window = 1.hours)
    }
}
