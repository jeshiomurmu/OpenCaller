package io.github.jeshiomurmu.opencaller.server.voip

import io.github.jeshiomurmu.opencaller.server.db.AssistantCalls
import io.github.jeshiomurmu.opencaller.server.db.CallInvites
import io.github.jeshiomurmu.opencaller.server.db.FlashMessages
import io.github.jeshiomurmu.opencaller.server.db.PushItems
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import java.time.Clock
import java.time.OffsetDateTime

/** What one cleanup removed. */
@Serializable
data class VoipCleanupResult(
    val pushItems: Int,
    val flashMessages: Int,
    val callInvites: Int,
    val assistantCalls: Int = 0,
)

/**
 * Hourly: deletes expired push items and undelivered Flash messages, and app-call invites a day after they were
 * made, so the server never builds up a history of who called or pinged whom (ADR 0007); assistant calls a day
 * after they started (ADR 0010; the minutes stay in `assistant_usage`).
 */
class VoipCleanupJob(
    private val db: ServerDatabase,
    private val clock: Clock,
) {
    /** Runs one cleanup. */
    suspend fun run(): VoipCleanupResult {
        val now = OffsetDateTime.now(clock)
        return db.query {
            VoipCleanupResult(
                pushItems = PushItems.deleteWhere { expiresAt less now },
                flashMessages = FlashMessages.deleteWhere { expiresAt less now },
                callInvites = CallInvites.deleteWhere { createdAt less now.minusHours(INVITE_KEEP_HOURS) },
                assistantCalls = AssistantCalls.deleteWhere { startedAt less now.minusHours(INVITE_KEEP_HOURS) },
            )
        }
    }

    /** Job name and retention. */
    companion object {
        /** Name of the job. */
        const val JOB_NAME = "voip-cleanup"
        private const val INVITE_KEEP_HOURS = 24L
    }
}
