package io.github.jeshiomurmu.opencaller.server.family

import io.github.jeshiomurmu.opencaller.server.db.FamilyAlerts
import io.github.jeshiomurmu.opencaller.server.db.FamilyDailyCounts
import io.github.jeshiomurmu.opencaller.server.db.FamilyGroups
import io.github.jeshiomurmu.opencaller.server.db.FamilyMembers
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.family.FamilyGuards.memberOf
import io.github.jeshiomurmu.opencaller.server.family.FamilyRows.alertFor
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.push.PushKind
import io.github.jeshiomurmu.opencaller.server.push.PushService
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * What members share with their admin, and only when they chose to (ADR 0008): alerts (`otp_during_call`,
 * `otp_burst`) and daily counts (calls blocked, messages filtered). Nothing else arrives: no number, no message
 * text, no contact. An alert reaches the admin like a Flash message, as a push carrying only a random item id.
 */
class FamilyAlertService(
    private val db: ServerDatabase,
    private val push: PushService,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    /** [userId]'s phone raised [request]'s alert: stored and pushed to the group's admin (TC-FAM-002). */
    suspend fun alert(
        userId: UUID,
        request: FamilyAlertRequest,
    ): FamilyAlertSent {
        requireValid(request.kind in FamilyRules.ALERT_KINDS) {
            invalidFamily("kind must be one of ${FamilyRules.ALERT_KINDS.sorted().joinToString()}.")
        }
        enforce(rateLimiter.hit(ALERTS_PER_MEMBER, userId.toString()), "Too many family alerts")
        val alertId = UUID.randomUUID()
        val adminId =
            db.query {
                val me = memberOf(userId)
                requireValid(me[FamilyMembers.role] == FamilyRules.MEMBER && me[FamilyMembers.shareOtpAlerts], ::notShared)
                val groupId = me[FamilyMembers.groupId]
                FamilyAlerts.insert {
                    it[id] = alertId
                    it[FamilyAlerts.groupId] = groupId
                    it[memberId] = userId
                    it[kind] = request.kind
                    it[createdAt] = now()
                }
                FamilyGroups.select(FamilyGroups.ownerId).where { FamilyGroups.id eq groupId }.single()[FamilyGroups.ownerId]
            }
        val (_, reached) = push.notify(adminId, PushKind.FAMILY_ALERT, alertId, ALERT_TTL_SECONDS, urgent = true)
        return FamilyAlertSent(alertId.toString(), reached)
    }

    /** [userId]'s counts for [rawDay] (`yyyy-mm-dd`, from two days ago to tomorrow in UTC, for time zones). */
    suspend fun counts(
        userId: UUID,
        rawDay: String,
        request: FamilyCountsRequest,
    ) {
        val day = dayOf(rawDay)
        val today = LocalDate.now(clock)
        requireValid(!day.isBefore(today.minusDays(MAX_DAYS_BACK)) && !day.isAfter(today.plusDays(1))) {
            invalidFamily("day must be between $MAX_DAYS_BACK days ago and tomorrow.")
        }
        requireValid(request.blockedCalls in 0..FamilyRules.MAX_COUNT && request.spamMessages in 0..FamilyRules.MAX_COUNT) {
            invalidFamily("Counts must be between 0 and ${FamilyRules.MAX_COUNT}.")
        }
        enforce(rateLimiter.hit(COUNTS_PER_MEMBER, userId.toString()), "Too many count updates")
        db.query {
            val me = memberOf(userId)
            requireValid(me[FamilyMembers.role] == FamilyRules.MEMBER && me[FamilyMembers.shareDailyCounts], ::notShared)
            FamilyDailyCounts.upsert {
                it[memberId] = userId
                it[FamilyDailyCounts.day] = day
                it[blockedCalls] = request.blockedCalls
                it[spamMessages] = request.spamMessages
                it[updatedAt] = now()
            }
        }
    }

    /** Alert [alertId] for the admin [userId] (what a `family_alert` push stands for), or `null` if gone. */
    suspend fun alertView(
        userId: UUID,
        alertId: UUID,
    ): FamilyAlertView? = db.query { alertFor(userId, alertId) }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun dayOf(raw: String): LocalDate =
        try {
            LocalDate.parse(raw)
        } catch (e: DateTimeParseException) {
            throw invalidFamily("day must be a date such as 2026-11-09 (${e.errorIndex}).")
        }

    /** Limits. */
    companion object {
        /** An alert's push is worth delivering for an hour; the admin sees the alert in the group for 30 days. */
        const val ALERT_TTL_SECONDS = 60 * 60
        private const val MAX_DAYS_BACK = 2L
        private val ALERTS_PER_MEMBER = RateLimit("family_alerts", maxHits = 10, window = 1.hours)
        private val COUNTS_PER_MEMBER = RateLimit("family_counts", maxHits = 24, window = 1.days)
    }
}
