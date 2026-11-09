package io.github.jeshiomurmu.opencaller.server.family

import io.github.jeshiomurmu.opencaller.server.db.FamilyAlerts
import io.github.jeshiomurmu.opencaller.server.db.FamilyDailyCounts
import io.github.jeshiomurmu.opencaller.server.db.FamilyGroups
import io.github.jeshiomurmu.opencaller.server.db.FamilyInvites
import io.github.jeshiomurmu.opencaller.server.db.FamilyMembers
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.family.FamilyGuards.adminOf
import io.github.jeshiomurmu.opencaller.server.family.FamilyGuards.forget
import io.github.jeshiomurmu.opencaller.server.family.FamilyGuards.memberOf
import io.github.jeshiomurmu.opencaller.server.family.FamilyGuards.openInvite
import io.github.jeshiomurmu.opencaller.server.family.FamilyGuards.requireOutside
import io.github.jeshiomurmu.opencaller.server.family.FamilyGuards.requireRoom
import io.github.jeshiomurmu.opencaller.server.family.FamilyGuards.viewOf
import io.github.jeshiomurmu.opencaller.server.family.FamilyRows.membershipOf
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Family groups (docs/prd.md F-PR-14, ADR 0008): up to [FamilyRules.MAX_MEMBERS] people, one admin. Nobody can be
 * added by someone else: a membership is written only by the person's own request, the admin's when creating the
 * group and everyone else's when joining with a single-use invite code after the consent screen (TC-FAM-001).
 * There is no endpoint that takes another person's number.
 */
class FamilyService(
    private val db: ServerDatabase,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Creates a group with [userId] as its admin. */
    suspend fun create(
        userId: UUID,
        request: CreateFamilyRequest,
    ): FamilyView {
        FamilyRules.checkLabel(request.name, "name")
        FamilyRules.checkLabel(request.label, "label")
        return db.query {
            requireOutside(userId)
            val now = now()
            val groupId = UUID.randomUUID()
            FamilyGroups.insert {
                it[id] = groupId
                it[ownerId] = userId
                it[name] = request.name
                it[createdAt] = now
            }
            FamilyMembers.insert {
                it[FamilyMembers.groupId] = groupId
                it[FamilyMembers.userId] = userId
                it[role] = FamilyRules.ADMIN
                it[label] = request.label
                it[consentedAt] = now
                it[consentVersion] = FamilyRules.CONSENT_VERSION
                it[updatedAt] = now
            }
            viewOf(userId, clock)
        }
    }

    /** [userId]'s group as they may see it; 404 `not_in_family` without one. */
    suspend fun view(userId: UUID): FamilyView = db.query { viewOf(userId, clock) }

    /** A new single-use invite code for [userId]'s group (admin only). */
    suspend fun invite(userId: UUID): FamilyInvite {
        enforce(rateLimiter.hit(INVITES_PER_ADMIN, userId.toString()), "Too many invite codes")
        val code = newCode(random)
        val expires = now().plusHours(FamilyRules.INVITE_HOURS)
        db.query {
            val groupId = adminOf(userId)[FamilyMembers.groupId]
            requireRoom(groupId)
            val open =
                FamilyInvites
                    .select(FamilyInvites.codeHash)
                    .where {
                        (FamilyInvites.groupId eq groupId) and FamilyInvites.usedAt.isNull() and
                            (FamilyInvites.expiresAt greater now())
                    }.count()
            requireValid(open < MAX_OPEN_INVITES) {
                ApiProblem(HttpStatusCode.Conflict, "too_many_invites", "Too many open invite codes", "Wait until one is used or expires.")
            }
            FamilyInvites.insert {
                it[codeHash] = hash(code)
                it[FamilyInvites.groupId] = groupId
                it[createdAt] = now()
                it[expiresAt] = expires
            }
        }
        return FamilyInvite(code, expires.toInstant().toString())
    }

    /** What joining with [code] means, for the consent screen; nothing is shared or changed by asking. */
    suspend fun preview(
        userId: UUID,
        code: String,
    ): FamilyInvitePreview {
        enforce(rateLimiter.hit(CODE_TRIES_PER_USER, userId.toString()), "Too many invite codes tried")
        return db.query {
            val invite = openInvite(code, now())
            val groupId = invite[FamilyInvites.groupId]
            val group = FamilyGroups.selectAll().where { FamilyGroups.id eq groupId }.single()
            val members = FamilyMembers.selectAll().where { FamilyMembers.groupId eq groupId }.toList()
            FamilyInvitePreview(
                groupName = group[FamilyGroups.name],
                adminLabel = members.first { it[FamilyMembers.role] == FamilyRules.ADMIN }[FamilyMembers.label],
                members = members.size,
                maxMembers = FamilyRules.MAX_MEMBERS,
                expiresAt = invite[FamilyInvites.expiresAt].toInstant().toString(),
                consentVersion = FamilyRules.CONSENT_VERSION,
            )
        }
    }

    /** [userId] joins with [request]'s code after agreeing on their own phone (TC-FAM-001). */
    suspend fun join(
        userId: UUID,
        request: JoinFamilyRequest,
    ): FamilyView {
        FamilyRules.checkLabel(request.label, "label")
        requireValid(request.consent) {
            ApiProblem(
                HttpStatusCode.BadRequest,
                "consent_required",
                "Joining needs your consent",
                "Read what the admin will see and agree on your own phone.",
            )
        }
        requireValid(request.consentVersion == FamilyRules.CONSENT_VERSION) {
            ApiProblem(HttpStatusCode.Conflict, "consent_outdated", "The consent text changed", "Update the app and read it again.")
        }
        enforce(rateLimiter.hit(CODE_TRIES_PER_USER, userId.toString()), "Too many invite codes tried")
        return db.query {
            requireOutside(userId)
            val now = now()
            val invite = openInvite(request.code, now, forUpdate = true)
            val groupId = invite[FamilyInvites.groupId]
            requireRoom(groupId, lock = true)
            FamilyMembers.insert {
                it[FamilyMembers.groupId] = groupId
                it[FamilyMembers.userId] = userId
                it[role] = FamilyRules.MEMBER
                it[label] = request.label
                it[consentedAt] = now
                it[consentVersion] = request.consentVersion
                it[shareOtpAlerts] = request.shareOtpAlerts
                it[shareDailyCounts] = request.shareDailyCounts
                it[updatedAt] = now
            }
            FamilyInvites.update({ FamilyInvites.codeHash eq invite[FamilyInvites.codeHash] }) { it[usedAt] = now }
            viewOf(userId, clock)
        }
    }

    /** Changes [userId]'s label and what they share; turning sharing off deletes what the server holds of it. */
    suspend fun updateMe(
        userId: UUID,
        request: FamilySharingRequest,
    ): FamilyView {
        FamilyRules.checkLabel(request.label, "label")
        return db.query {
            val isMember = memberOf(userId)[FamilyMembers.role] == FamilyRules.MEMBER
            // The admin shares nothing: alerts and counts go from members to the admin only.
            val otp = isMember && request.shareOtpAlerts
            val counts = isMember && request.shareDailyCounts
            FamilyMembers.update({ FamilyMembers.userId eq userId }) {
                it[label] = request.label
                it[shareOtpAlerts] = otp
                it[shareDailyCounts] = counts
                it[updatedAt] = now()
            }
            if (!otp) FamilyAlerts.deleteWhere { memberId eq userId }
            if (!counts) FamilyDailyCounts.deleteWhere { memberId eq userId }
            viewOf(userId, clock)
        }
    }

    /** [userId] leaves; an admin leaving ends the group for everyone. What the server held about them is deleted. */
    suspend fun leave(userId: UUID) {
        db.query {
            val me = memberOf(userId)
            val groupId = me[FamilyMembers.groupId]
            if (me[FamilyMembers.role] == FamilyRules.ADMIN) {
                val everyone =
                    FamilyMembers
                        .select(
                            FamilyMembers.userId,
                        ).where { FamilyMembers.groupId eq groupId }
                        .map { it[FamilyMembers.userId] }
                FamilyDailyCounts.deleteWhere { memberId inList everyone }
                // Members, invites and alerts go with the group (ON DELETE CASCADE).
                FamilyGroups.deleteWhere { id eq groupId }
            } else {
                forget(userId)
            }
        }
    }

    /** The admin [userId] removes [memberId] from their group. */
    suspend fun remove(
        userId: UUID,
        memberId: UUID,
    ) {
        db.query {
            val me = adminOf(userId)
            val member = membershipOf(memberId)
            val sameGroup = member != null && member[FamilyMembers.groupId] == me[FamilyMembers.groupId]
            if (!sameGroup || memberId == userId) {
                throw ApiProblem(HttpStatusCode.NotFound, "member_not_found", "No such member in your group")
            }
            forget(memberId)
        }
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    /** Code format and limits. */
    companion object {
        /** No 0/O, 1/I: codes are read out and typed. 32 symbols, 8 characters: 40 bits. */
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val CODE_LENGTH = 8
        private const val MAX_OPEN_INVITES = 5
        private val INVITES_PER_ADMIN = RateLimit("family_invites", maxHits = 10, window = 1.days)
        private val CODE_TRIES_PER_USER = RateLimit("family_code_tries", maxHits = 20, window = 1.hours)

        /** A random code from [CODE_ALPHABET]. */
        fun newCode(random: SecureRandom): String = String(CharArray(CODE_LENGTH) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] })

        /** [code] in upper case without spaces or dashes, or `null` if it can't be a code. */
        fun normalize(code: String): String? =
            code
                .filterNot { it == ' ' || it == '-' }
                .uppercase()
                .takeIf { it.length == CODE_LENGTH && it.all { c -> c in CODE_ALPHABET } }

        /** SHA-256 (hex) of a normalized code: the only form the server keeps. */
        fun hash(normalized: String): String =
            MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8)).toHexString()
    }
}
