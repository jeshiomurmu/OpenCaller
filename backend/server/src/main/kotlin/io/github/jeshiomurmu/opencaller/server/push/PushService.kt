package io.github.jeshiomurmu.opencaller.server.push

import io.github.jeshiomurmu.opencaller.server.db.Devices
import io.github.jeshiomurmu.opencaller.server.db.PushItems
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.isPlainText
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** `PUT /v1/push`: this device's push endpoint. `type` is `fcm` (with [token]) or `webpush` (with the rest). */
@Serializable
data class PushRegistration(
    val type: String,
    val token: String? = null,
    val endpoint: String? = null,
    val p256dh: String? = null,
    val auth: String? = null,
)

/** What a push stands for (`push_items.kind`). */
enum class PushKind(
    val sqlValue: String,
) {
    CALL_INVITE("call_invite"),
    CALL_UPDATE("call_update"),
    FLASH("flash"),
    FAMILY_ALERT("family_alert"),
    ;

    /** Lookup by stored value. */
    companion object {
        /** The kind stored as [value]. */
        fun of(value: String): PushKind = entries.first { it.sqlValue == value }
    }
}

/** A stored push item: [refId] is the invite, Flash message or family alert it stands for. */
data class PushItem(
    val id: UUID,
    val kind: PushKind,
    val refId: UUID,
    val createdAt: OffsetDateTime,
)

/**
 * Push endpoints and push items (ADR 0007). A push carries only a push item's random id; the app fetches the item
 * (`GET /v1/notifications/{id}`) to learn what it is, so FCM and the UnifiedPush distributor never see who calls
 * whom. Endpoints that the push service reports gone are removed.
 */
class PushService(
    private val db: ServerDatabase,
    private val sender: PushSender,
    private val policy: PushEndpointPolicy,
    private val clock: Clock,
    /** True when a push provider is configured (app calls and Flash messages need one). */
    val enabled: Boolean = true,
) {
    /** Stores [registration] as the push endpoint of [deviceId] (one of [userId]'s devices). */
    suspend fun register(
        userId: UUID,
        deviceId: UUID,
        registration: PushRegistration,
    ) {
        val target = PushRegistrations.validated(registration, policy)
        val now = OffsetDateTime.now(clock)
        val updated =
            db.query {
                Devices.update({ (Devices.id eq deviceId) and (Devices.userId eq userId) }) {
                    when (target) {
                        is PushTarget.Fcm -> {
                            it[pushType] = PushRegistrations.FCM
                            it[pushToken] = target.token
                            it[pushP256dh] = null
                            it[pushAuth] = null
                        }

                        is PushTarget.WebPush -> {
                            it[pushType] = PushRegistrations.WEB_PUSH
                            it[pushToken] = target.endpoint.toString()
                            it[pushP256dh] = registration.p256dh
                            it[pushAuth] = registration.auth
                        }
                    }
                    it[pushUpdatedAt] = now
                }
            }
        requireValid(updated == 1) { ApiProblem(HttpStatusCode.NotFound, "device_not_found", "This device is not signed in") }
    }

    /** Removes [deviceId]'s push endpoint. */
    suspend fun unregister(
        userId: UUID,
        deviceId: UUID,
    ) {
        db.query { clearEndpoint(deviceId, userId) }
    }

    /**
     * Records a [kind] item for [userId] and pushes its id to each of their devices, waiting at most
     * [WAIT] for the push services. Returns the item and how many devices were reached.
     */
    suspend fun notify(
        userId: UUID,
        kind: PushKind,
        refId: UUID,
        ttlSeconds: Int,
        urgent: Boolean,
    ): Pair<UUID, Int> {
        val itemId = UUID.randomUUID()
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        val targets =
            db.query {
                PushItems.insert {
                    it[id] = itemId
                    it[PushItems.userId] = userId
                    it[PushItems.kind] = kind.sqlValue
                    it[PushItems.refId] = refId
                    it[createdAt] = now
                    it[expiresAt] = now.plusSeconds(ttlSeconds.toLong())
                }
                targetsOf(userId)
            }
        val outcomes =
            withTimeoutOrNull(WAIT) {
                coroutineScope {
                    targets
                        .map { (device, target) ->
                            async { device to sender.send(target, itemId, ttlSeconds, urgent) }
                        }.awaitAll()
                }
            }.orEmpty()
        val gone = outcomes.filter { it.second == PushOutcome.GONE }.map { it.first }
        if (gone.isNotEmpty()) db.query { gone.forEach { clearEndpoint(it, userId) } }
        return itemId to outcomes.count { it.second == PushOutcome.DELIVERED }
    }

    /** [userId]'s push item [id], if it exists and hasn't expired. */
    suspend fun item(
        userId: UUID,
        id: UUID,
    ): PushItem? =
        db.query {
            PushItems
                .selectAll()
                .where { (PushItems.id eq id) and (PushItems.userId eq userId) and (PushItems.expiresAt greater OffsetDateTime.now(clock)) }
                .map(::toItem)
                .firstOrNull()
        }

    /** [userId]'s unexpired push items, newest first (for phones whose push didn't arrive). */
    suspend fun pending(userId: UUID): List<PushItem> =
        db.query {
            PushItems
                .selectAll()
                .where { (PushItems.userId eq userId) and (PushItems.expiresAt greater OffsetDateTime.now(clock)) }
                .orderBy(PushItems.createdAt, org.jetbrains.exposed.v1.core.SortOrder.DESC)
                .limit(PENDING_LIMIT)
                .map(::toItem)
        }

    private fun toItem(row: org.jetbrains.exposed.v1.core.ResultRow) =
        PushItem(row[PushItems.id], PushKind.of(row[PushItems.kind]), row[PushItems.refId], row[PushItems.createdAt])

    private fun org.jetbrains.exposed.v1.jdbc.JdbcTransaction.targetsOf(userId: UUID): List<Pair<UUID, PushTarget>> =
        Devices
            .selectAll()
            .where { (Devices.userId eq userId) and Devices.pushType.isNotNull() and Devices.pushToken.isNotNull() }
            .mapNotNull { row ->
                val token = row[Devices.pushToken] ?: return@mapNotNull null
                val target =
                    when (row[Devices.pushType]) {
                        PushRegistrations.FCM -> PushTarget.Fcm(token)
                        PushRegistrations.WEB_PUSH -> webPushTarget(token, row[Devices.pushP256dh], row[Devices.pushAuth])
                        else -> null
                    }
                target?.let { row[Devices.id] to it }
            }

    private fun webPushTarget(
        endpoint: String,
        p256dh: String?,
        auth: String?,
    ): PushTarget? {
        if (p256dh == null || auth == null) return null
        // Checked again before each push: the endpoint's host may resolve differently now (DNS rebinding).
        return try {
            PushTarget.WebPush(policy.check(endpoint), HttpPushSender.base64Url(p256dh), HttpPushSender.base64Url(auth))
        } catch (e: ApiProblem) {
            log.info("Web Push endpoint skipped: {}", e.detail)
            null
        }
    }

    private fun clearEndpoint(
        deviceId: UUID,
        userId: UUID,
    ) {
        Devices.update({ (Devices.id eq deviceId) and (Devices.userId eq userId) }) {
            it[pushType] = null
            it[pushToken] = null
            it[pushP256dh] = null
            it[pushAuth] = null
            it[pushUpdatedAt] = null
        }
    }

    private companion object {
        const val PENDING_LIMIT = 50
        val WAIT = 3.seconds
        val log = LoggerFactory.getLogger(PushService::class.java)
    }
}
