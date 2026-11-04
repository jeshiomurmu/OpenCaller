package io.github.jeshiomurmu.opencaller.server.notifications

import io.github.jeshiomurmu.opencaller.server.flash.FlashService
import io.github.jeshiomurmu.opencaller.server.flash.FlashView
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.push.PushItem
import io.github.jeshiomurmu.opencaller.server.push.PushKind
import io.github.jeshiomurmu.opencaller.server.push.PushService
import io.github.jeshiomurmu.opencaller.server.voip.InviteView
import io.github.jeshiomurmu.opencaller.server.voip.VoipService
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * What a push stood for: [kind] is `call_invite`, `call_update` (with [call]) or `flash` (with [flash]).
 */
@Serializable
data class NotificationView(
    val id: String,
    val kind: String,
    val createdAt: String,
    val call: InviteView? = null,
    val flash: FlashView? = null,
)

/** `GET /v1/notifications`: the items behind pushes, for the push that just arrived or for a phone that missed some. */
class NotificationService(
    private val push: PushService,
    private val voip: VoipService,
    private val flash: FlashService,
) {
    /** Push item [id] of [userId], resolved; 404 `notification_not_found` if it is gone. */
    suspend fun item(
        userId: UUID,
        id: UUID,
    ): NotificationView = push.item(userId, id)?.let { resolve(userId, it) } ?: throw gone()

    /** [userId]'s unexpired items, newest first; Flash messages that were already fetched are left out. */
    suspend fun pending(userId: UUID): List<NotificationView> = push.pending(userId).mapNotNull { resolve(userId, it) }

    private suspend fun resolve(
        userId: UUID,
        item: PushItem,
    ): NotificationView? {
        val base = NotificationView(item.id.toString(), item.kind.sqlValue, item.createdAt.toInstant().toString())
        return when (item.kind) {
            PushKind.CALL_INVITE, PushKind.CALL_UPDATE -> {
                callView(userId, item.refId)?.let { base.copy(call = it) }
            }

            PushKind.FLASH -> {
                flash.receive(userId, item.refId)?.let { base.copy(flash = it) }
            }
        }
    }

    // The invite may already have been cleaned up (a day after the call): then the item has nothing left to show.
    private suspend fun callView(
        userId: UUID,
        inviteId: UUID,
    ): InviteView? =
        try {
            voip.view(userId, inviteId)
        } catch (e: ApiProblem) {
            log.debug("Call {} for a push item is gone: {}", inviteId, e.code)
            null
        }

    private fun gone() = ApiProblem(HttpStatusCode.NotFound, "notification_not_found", "This notification is gone")
}

private val log = LoggerFactory.getLogger(NotificationService::class.java)
