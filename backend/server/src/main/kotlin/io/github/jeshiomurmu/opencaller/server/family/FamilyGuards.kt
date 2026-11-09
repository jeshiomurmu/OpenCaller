package io.github.jeshiomurmu.opencaller.server.family

import io.github.jeshiomurmu.opencaller.server.db.FamilyAlerts
import io.github.jeshiomurmu.opencaller.server.db.FamilyDailyCounts
import io.github.jeshiomurmu.opencaller.server.db.FamilyGroups
import io.github.jeshiomurmu.opencaller.server.db.FamilyInvites
import io.github.jeshiomurmu.opencaller.server.db.FamilyMembers
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.family.FamilyRows.membershipOf
import io.github.jeshiomurmu.opencaller.server.family.FamilyRows.viewFor
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

/** The checks family requests start with, each answering with its problem when it fails. */
internal object FamilyGuards {
    /** [userId]'s membership, or 404 `not_in_family`. */
    fun JdbcTransaction.memberOf(userId: UUID): ResultRow = membershipOf(userId) ?: throw notInFamily()

    /** [userId]'s membership if they are the admin, else 404 `not_in_family` or 403 `family_admin_only`. */
    fun JdbcTransaction.adminOf(userId: UUID): ResultRow {
        val me = memberOf(userId)
        if (me[FamilyMembers.role] != FamilyRules.ADMIN) throw adminOnly()
        return me
    }

    /** Locks [userId]'s row (one create or join at a time per person) and checks they are in no group yet. */
    fun JdbcTransaction.requireOutside(userId: UUID) {
        Users
            .select(Users.id)
            .where { Users.id eq userId }
            .forUpdate()
            .single()
        if (membershipOf(userId) != null) throw alreadyInFamily()
    }

    /** 409 `family_full` unless group [groupId] has room; [lock] holds the group row so two joins can't both fit in. */
    fun JdbcTransaction.requireRoom(
        groupId: UUID,
        lock: Boolean = false,
    ) {
        if (lock) {
            FamilyGroups
                .selectAll()
                .where { FamilyGroups.id eq groupId }
                .forUpdate()
                .single()
        }
        val members = FamilyMembers.select(FamilyMembers.userId).where { FamilyMembers.groupId eq groupId }.count()
        if (members >= FamilyRules.MAX_MEMBERS) throw familyFull()
    }

    /** [userId]'s view of their group, or 404 `not_in_family`. */
    fun JdbcTransaction.viewOf(
        userId: UUID,
        clock: Clock,
    ): FamilyView = viewFor(userId, clock) ?: throw notInFamily()

    /** The unused, unexpired invite with [code], or 404 `invite_not_found` (the same answer for every reason). */
    fun JdbcTransaction.openInvite(
        code: String,
        now: OffsetDateTime,
        forUpdate: Boolean = false,
    ): ResultRow {
        val normalized = FamilyService.normalize(code) ?: throw inviteNotFound()
        return FamilyInvites
            .selectAll()
            .where {
                (FamilyInvites.codeHash eq FamilyService.hash(normalized)) and FamilyInvites.usedAt.isNull() and
                    (FamilyInvites.expiresAt greater now)
            }.let { if (forUpdate) it.forUpdate() else it }
            .singleOrNull() ?: throw inviteNotFound()
    }

    /** Takes [memberId] out of their group with everything the server held about them. */
    fun JdbcTransaction.forget(memberId: UUID) {
        FamilyAlerts.deleteWhere { FamilyAlerts.memberId eq memberId }
        FamilyDailyCounts.deleteWhere { FamilyDailyCounts.memberId eq memberId }
        FamilyMembers.deleteWhere { userId eq memberId }
    }
}
