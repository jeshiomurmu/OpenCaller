package io.github.jeshiomurmu.opencaller.server.family

import io.github.jeshiomurmu.opencaller.server.db.BusinessSearchCounts
import io.github.jeshiomurmu.opencaller.server.db.FamilyAlerts
import io.github.jeshiomurmu.opencaller.server.db.FamilyDailyCounts
import io.github.jeshiomurmu.opencaller.server.db.FamilyInvites
import io.github.jeshiomurmu.opencaller.server.db.PortalSessions
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime

/** What one expiry run removed. */
@Serializable
data class ExpiryResult(
    val familyInvites: Int,
    val familyAlerts: Int,
    val familyCounts: Int,
    val businessSearchDays: Int,
    val portalSessions: Int,
)

/**
 * Hourly (ADR 0008): deletes used and expired family invites, family alerts after 30 days, members' daily counts
 * once the admin's 7-day summary no longer shows them, search counts of business numbers after 90 days and
 * expired dashboard sessions.
 */
class ExpiryJob(
    private val db: ServerDatabase,
    private val clock: Clock,
) {
    /** Runs once (the caller holds the leader lock). */
    suspend fun run(): ExpiryResult {
        val now = OffsetDateTime.now(clock)
        val today = LocalDate.now(clock)
        return db.query {
            ExpiryResult(
                familyInvites = FamilyInvites.deleteWhere { (expiresAt less now) or usedAt.isNotNull() },
                familyAlerts = FamilyAlerts.deleteWhere { createdAt less now.minusDays(FamilyRules.ALERT_DAYS) },
                familyCounts = FamilyDailyCounts.deleteWhere { day less today.minusDays(FamilyRules.SUMMARY_DAYS) },
                businessSearchDays = BusinessSearchCounts.deleteWhere { day less today.minusDays(SEARCH_DAYS) },
                portalSessions = PortalSessions.deleteWhere { expiresAt less now },
            )
        }
    }

    /** Job name and retention. */
    companion object {
        /** Name of the job. */
        const val JOB_NAME = "expiry"
        private const val SEARCH_DAYS = 90L
    }
}
