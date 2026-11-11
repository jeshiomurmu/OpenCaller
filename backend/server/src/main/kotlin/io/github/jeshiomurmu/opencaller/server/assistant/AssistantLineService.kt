package io.github.jeshiomurmu.opencaller.server.assistant

import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.business.regionOf
import io.github.jeshiomurmu.opencaller.server.config.AssistantConfig
import io.github.jeshiomurmu.opencaller.server.db.AssistantNumbers
import io.github.jeshiomurmu.opencaller.server.db.AssistantUsage
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.profile.Entitlements
import io.github.jeshiomurmu.opencaller.server.profile.activeUser
import io.github.jeshiomurmu.opencaller.server.profile.supporterRequired
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.notInList
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.hours

/**
 * Assistant numbers (docs/prd.md F-UT-04, ADR 0010): each user who turns the assistant on gets a number of their
 * own from the operator's pool (`ASSISTANT_NUMBERS`), in their country, and forwards unanswered calls to it. The
 * agent finds the user by the number the caller reached, so the forwarding network's headers don't matter.
 */
class AssistantLineService(
    private val db: ServerDatabase,
    private val config: AssistantConfig,
    private val entitlements: Entitlements,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    private val poolLock = Mutex()
    private var poolSynced = false

    /** `true` when this server has assistant numbers. */
    val enabled: Boolean get() = config.numbers.isNotEmpty()

    /** [userId]'s number: the one they have, or a free one in their country (supporters, or all on a self-hosted server). */
    suspend fun allocate(userId: UUID): AssistantLineView {
        requireValid(enabled) { assistantUnavailable("This server has no assistant numbers.") }
        requireValid(entitlements.everyone || entitlements.isSupporter(userId), ::supporterRequired)
        syncPool()
        val country = db.query { activeUser(userId)[Users.e164] }.let { E164.parseOrNull(it)?.let(::regionOf) }
        requireValid(country != null) { assistantUnavailable("Your number has no country.") }
        enforce(rateLimiter.hit(ALLOCATIONS, userId.toString()), "Too many assistant number requests")
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        return db.query {
            lineOf(userId) ?: run {
                val free =
                    AssistantNumbers
                        .select(AssistantNumbers.e164)
                        .where {
                            (AssistantNumbers.countryIso eq checkNotNull(country)) and AssistantNumbers.userId.isNull() and
                                (AssistantNumbers.enabled eq true)
                        }.orderBy(AssistantNumbers.e164)
                        .limit(1)
                        .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                        .singleOrNull()
                        ?.get(AssistantNumbers.e164)
                        ?: throw assistantUnavailable("There is no free assistant number for your country.")
                AssistantNumbers.update({ AssistantNumbers.e164 eq free }) {
                    it[AssistantNumbers.userId] = userId
                    it[assignedAt] = now
                }
                AssistantLineView(free, checkNotNull(country))
            }
        }
    }

    /** [userId]'s number; 404 `assistant_line_not_found` if they have none. */
    suspend fun line(userId: UUID): AssistantLineView {
        syncPool()
        return db.query { lineOf(userId) } ?: throw noAssistantLine()
    }

    /** Gives [userId]'s number back to the pool (the app first asks the user to cancel the forwarding). */
    suspend fun release(userId: UUID) {
        db.query {
            AssistantNumbers.update({ AssistantNumbers.userId eq userId }) {
                it[AssistantNumbers.userId] = null
                it[assignedAt] = null
            }
        }
    }

    /** [userId]'s minutes this month. */
    suspend fun usage(userId: UUID): AssistantUsageView {
        val month = monthOf(clock)
        val seconds = db.query { usedSeconds(userId, month) }
        return AssistantUsageView(month.toString().take(YEAR_MONTH), seconds, config.monthlyMinutes, config.maxCallMinutes)
    }

    /**
     * Brings the pool in line with `ASSISTANT_NUMBERS` once per process: new numbers are added, and a number the
     * operator removed is disabled and taken from its user (who gets a new one on the next request).
     */
    suspend fun syncPool() {
        poolLock.withLock {
            if (poolSynced) return
            db.query {
                config.numbers.forEach { (e164, iso) ->
                    AssistantNumbers.upsert(
                        onUpdate = {
                            it[AssistantNumbers.countryIso] = iso
                            it[AssistantNumbers.enabled] = true
                        },
                    ) {
                        it[AssistantNumbers.e164] = e164
                        it[countryIso] = iso
                        it[enabled] = true
                    }
                }
                val removed = if (config.numbers.isEmpty()) Op.TRUE else AssistantNumbers.e164 notInList config.numbers.keys
                AssistantNumbers.update({ removed and (AssistantNumbers.enabled eq true) }) {
                    it[enabled] = false
                    it[userId] = null
                    it[assignedAt] = null
                }
            }
            poolSynced = true
        }
    }

    private fun JdbcTransaction.lineOf(userId: UUID): AssistantLineView? =
        AssistantNumbers
            .selectAll()
            .where { (AssistantNumbers.userId eq userId) and (AssistantNumbers.enabled eq true) }
            .singleOrNull()
            ?.let { AssistantLineView(it[AssistantNumbers.e164], it[AssistantNumbers.countryIso]) }

    private companion object {
        const val YEAR_MONTH = 7
        val ALLOCATIONS = RateLimit("assistant_lines", maxHits = 10, window = 24.hours)
    }
}

/** The first day of the current calendar month in UTC (the key of `assistant_usage`). */
internal fun monthOf(clock: Clock): LocalDate = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1)

/** Seconds [userId] used in [month]. */
internal fun JdbcTransaction.usedSeconds(
    userId: UUID,
    month: LocalDate,
): Int =
    AssistantUsage
        .select(AssistantUsage.seconds)
        .where { (AssistantUsage.userId eq userId) and (AssistantUsage.month eq month) }
        .singleOrNull()
        ?.get(AssistantUsage.seconds) ?: 0
