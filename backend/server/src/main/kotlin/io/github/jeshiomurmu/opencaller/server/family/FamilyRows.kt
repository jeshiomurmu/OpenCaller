package io.github.jeshiomurmu.opencaller.server.family

import io.github.jeshiomurmu.opencaller.server.db.FamilyAlerts
import io.github.jeshiomurmu.opencaller.server.db.FamilyDailyCounts
import io.github.jeshiomurmu.opencaller.server.db.FamilyGroups
import io.github.jeshiomurmu.opencaller.server.db.FamilyMembers
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** Reading family groups for [FamilyService] and the alerts in notifications. */
internal object FamilyRows {
    /** [userId]'s membership row, or `null`. */
    fun JdbcTransaction.membershipOf(userId: UUID): ResultRow? =
        FamilyMembers.selectAll().where { FamilyMembers.userId eq userId }.singleOrNull()

    /**
     * [userId]'s group as they may see it: everyone sees the group and their own membership; only the admin sees the
     * members, the counts members share (last [FamilyRules.SUMMARY_DAYS] days) and the alerts (last 30 days).
     */
    fun JdbcTransaction.viewFor(
        userId: UUID,
        clock: Clock,
    ): FamilyView? {
        val me = membershipOf(userId) ?: return null
        val groupId = me[FamilyMembers.groupId]
        val group = FamilyGroups.selectAll().where { FamilyGroups.id eq groupId }.single()
        val members =
            FamilyMembers
                .selectAll()
                .where { FamilyMembers.groupId eq groupId }
                .orderBy(FamilyMembers.consentedAt)
                .toList()
        val isAdmin = me[FamilyMembers.role] == FamilyRules.ADMIN
        return FamilyView(
            id = groupId.toString(),
            name = group[FamilyGroups.name],
            adminLabel = members.first { it[FamilyMembers.role] == FamilyRules.ADMIN }[FamilyMembers.label],
            memberCount = members.size,
            maxMembers = FamilyRules.MAX_MEMBERS,
            me = meOf(me),
            members = if (isAdmin) membersWithCounts(members, LocalDate.now(clock)) else emptyList(),
            alerts = if (isAdmin) alertsOf(groupId, members, OffsetDateTime.now(clock)) else emptyList(),
        )
    }

    /** Alert [alertId] as the admin [adminId] sees it, or `null` if it isn't theirs or is gone. */
    fun JdbcTransaction.alertFor(
        adminId: UUID,
        alertId: UUID,
    ): FamilyAlertView? {
        val row =
            FamilyAlerts
                .join(FamilyGroups, JoinType.INNER, FamilyAlerts.groupId, FamilyGroups.id)
                .join(FamilyMembers, JoinType.INNER, FamilyAlerts.memberId, FamilyMembers.userId)
                .selectAll()
                .where { (FamilyAlerts.id eq alertId) and (FamilyGroups.ownerId eq adminId) }
                .singleOrNull() ?: return null
        return alertView(row, row[FamilyMembers.label])
    }

    private fun meOf(row: ResultRow) =
        FamilyMe(
            label = row[FamilyMembers.label],
            role = row[FamilyMembers.role],
            shareOtpAlerts = row[FamilyMembers.shareOtpAlerts],
            shareDailyCounts = row[FamilyMembers.shareDailyCounts],
            consentedAt = row[FamilyMembers.consentedAt].toInstant().toString(),
            consentVersion = row[FamilyMembers.consentVersion],
        )

    private fun JdbcTransaction.membersWithCounts(
        members: List<ResultRow>,
        today: LocalDate,
    ): List<FamilyMemberView> {
        val sharing = members.filter { it[FamilyMembers.shareDailyCounts] }.map { it[FamilyMembers.userId] }
        val counts =
            if (sharing.isEmpty()) {
                emptyMap()
            } else {
                FamilyDailyCounts
                    .selectAll()
                    .where {
                        (FamilyDailyCounts.memberId inList sharing) and
                            (FamilyDailyCounts.day greaterEq today.minusDays(FamilyRules.SUMMARY_DAYS - 1))
                    }.orderBy(FamilyDailyCounts.day, SortOrder.DESC)
                    .groupBy({ it[FamilyDailyCounts.memberId] }) {
                        FamilyDayCounts(
                            it[FamilyDailyCounts.day].toString(),
                            it[FamilyDailyCounts.blockedCalls],
                            it[FamilyDailyCounts.spamMessages],
                        )
                    }
            }
        return members.map { row ->
            val id = row[FamilyMembers.userId]
            FamilyMemberView(
                id = id.toString(),
                label = row[FamilyMembers.label],
                role = row[FamilyMembers.role],
                joinedAt = row[FamilyMembers.consentedAt].toInstant().toString(),
                shareOtpAlerts = row[FamilyMembers.shareOtpAlerts],
                shareDailyCounts = row[FamilyMembers.shareDailyCounts],
                days = counts[id].orEmpty(),
            )
        }
    }

    private fun JdbcTransaction.alertsOf(
        groupId: UUID,
        members: List<ResultRow>,
        now: OffsetDateTime,
    ): List<FamilyAlertView> {
        val labels = members.associate { it[FamilyMembers.userId] to it[FamilyMembers.label] }
        return FamilyAlerts
            .selectAll()
            .where { (FamilyAlerts.groupId eq groupId) and (FamilyAlerts.createdAt greaterEq now.minusDays(FamilyRules.ALERT_DAYS)) }
            .orderBy(FamilyAlerts.createdAt, SortOrder.DESC)
            .limit(MAX_ALERTS)
            .mapNotNull { row -> labels[row[FamilyAlerts.memberId]]?.let { alertView(row, it) } }
    }

    private fun alertView(
        row: ResultRow,
        label: String,
    ) = FamilyAlertView(
        id = row[FamilyAlerts.id].toString(),
        memberId = row[FamilyAlerts.memberId].toString(),
        memberLabel = label,
        kind = row[FamilyAlerts.kind],
        at = row[FamilyAlerts.createdAt].toInstant().toString(),
    )

    private const val MAX_ALERTS = 100
}
