package io.github.jeshiomurmu.opencaller.server.portal

import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpDeliveryException
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpService
import io.github.jeshiomurmu.opencaller.server.auth.otp.invalidCodeFormat
import io.github.jeshiomurmu.opencaller.server.auth.otp.toProblem
import io.github.jeshiomurmu.opencaller.server.business.ClaimRules
import io.github.jeshiomurmu.opencaller.server.db.BusinessNumbers
import io.github.jeshiomurmu.opencaller.server.db.Businesses
import io.github.jeshiomurmu.opencaller.server.db.PortalSessions
import io.github.jeshiomurmu.opencaller.server.db.ReviewStatus
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.invalidNumber
import io.github.jeshiomurmu.opencaller.server.http.isPlainText
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.portal.PortalStats.dashboardOf
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration.Companion.hours

/** A signed-in dashboard session: the business, the number its holder proved, and the forms' CSRF token. */
data class PortalSession(
    val businessId: UUID,
    val e164: String,
    val csrf: String,
)

/** A new session: the cookie's [token] and when it ends. */
data class PortalLogin(
    val token: String,
    val expiresAt: OffsetDateTime,
)

/** What a business may change on the dashboard: website, opening hours and each number's call reason. */
data class PortalProfileForm(
    val website: String,
    val hours: String,
    val callReasons: Map<String, String>,
)

/**
 * The business dashboard (docs/prd.md F-BZ-04, ADR 0008): the holder of a number of an approved business signs in
 * with a one-time code sent to that number, then edits the profile fields that need no new review and reads daily
 * counts of what the server already holds about the business's numbers ([PortalStats]). Nothing about the calls
 * a business makes (answered, declined) is collected: apps send no such data (docs/architecture.md §10).
 */
class PortalService(
    private val db: ServerDatabase,
    private val otp: OtpService,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Minutes a code stays valid. */
    val codeValidMinutes: Long get() = otp.validFor.inWholeMinutes

    /**
     * Sends a code to [rawNumber] if it is a number of an approved business. The page answers the same either way
     * (business numbers are public in the directory, but the page needn't confirm which numbers have a dashboard).
     */
    suspend fun startLogin(
        rawNumber: String,
        clientIp: String,
    ): E164 {
        enforce(rateLimiter.hit(START_PER_IP, clientIp), TOO_MANY_CODES)
        val number = numberOf(rawNumber)
        enforce(rateLimiter.hit(START_PER_NUMBER, number.value), TOO_MANY_CODES)
        if (db.query { businessOf(number.value) } != null) {
            try {
                otp.start(number)
            } catch (e: OtpDeliveryException) {
                log.warn("Dashboard code for {} could not be delivered", number, e)
                throw ApiProblem(HttpStatusCode.ServiceUnavailable, "otp_delivery_failed", "The code could not be sent", "Try again later.")
            }
        }
        return number
    }

    /** Checks [code] for [rawNumber] and opens a session for its business. */
    suspend fun verify(
        rawNumber: String,
        code: String,
    ): PortalLogin {
        val number = numberOf(rawNumber)
        requireValid(OtpService.isWellFormed(code), ::invalidCodeFormat)
        otp.verify(number, code).toProblem()?.let { throw it }
        val token = randomToken()
        val expires = now().plusHours(SESSION_HOURS)
        db.query {
            val business =
                businessOf(number.value) ?: throw ApiProblem(HttpStatusCode.Forbidden, "not_a_business", "This number has no dashboard")
            PortalSessions.insert {
                it[tokenHash] = hash(token)
                it[businessId] = business
                it[e164] = number.value
                it[csrf] = randomToken()
                it[createdAt] = now()
                it[expiresAt] = expires
            }
        }
        return PortalLogin(token, expires)
    }

    /** The session of cookie [token], or `null` if it is unknown, expired or its business is no longer approved. */
    suspend fun session(token: String?): PortalSession? {
        if (token.isNullOrBlank() || token.length > MAX_TOKEN) return null
        return db.query {
            PortalSessions
                .join(Businesses, JoinType.INNER, PortalSessions.businessId, Businesses.id)
                .select(PortalSessions.businessId, PortalSessions.e164, PortalSessions.csrf)
                .where {
                    (PortalSessions.tokenHash eq hash(token)) and (PortalSessions.expiresAt greater now()) and
                        (Businesses.status eq ReviewStatus.APPROVED)
                }.singleOrNull()
                ?.let { PortalSession(it[PortalSessions.businessId], it[PortalSessions.e164], it[PortalSessions.csrf]) }
        }
    }

    /** Ends the session of [token]. */
    suspend fun logout(token: String?) {
        if (token.isNullOrBlank() || token.length > MAX_TOKEN) return
        db.query { PortalSessions.deleteWhere { tokenHash eq hash(token) } }
    }

    /** The business of [session] with its numbers and the last [PortalStats.DAYS] days of counts. */
    suspend fun dashboard(session: PortalSession): PortalDashboard = db.query { dashboardOf(session.businessId, clock) }

    /** Saves [form] for [session]'s business; returns a sentence for the page if a field is wrong. */
    suspend fun updateProfile(
        session: PortalSession,
        form: PortalProfileForm,
    ): String? {
        problemWith(form)?.let { return it }
        db.query {
            val now = now()
            Businesses.update({ Businesses.id eq session.businessId }) {
                it[website] = form.website.ifBlank { null }
                it[hours] = form.hours.ifBlank { null }
                it[updatedAt] = now
            }
            // Every number is touched, so apps pick the business up again with their next directory update.
            val numbers =
                BusinessNumbers
                    .select(BusinessNumbers.e164)
                    .where {
                        BusinessNumbers.businessId eq session.businessId
                    }.map { it[BusinessNumbers.e164] }
            numbers.forEach { number ->
                BusinessNumbers.update({ BusinessNumbers.e164 eq number }) {
                    form.callReasons[number]?.let { reason -> it[defaultCallReason] = reason.ifBlank { null } }
                    it[updatedAt] = now
                }
            }
        }
        return null
    }

    private fun problemWith(form: PortalProfileForm): String? =
        when {
            form.website.isNotBlank() && !isWebsite(form.website) -> {
                "The website must be an https:// address of at most ${ClaimRules.MAX_SHORT_TEXT} characters."
            }

            form.hours.isNotBlank() && !isPlainText(form.hours, ClaimRules.MAX_SHORT_TEXT) -> {
                "Opening hours must be at most ${ClaimRules.MAX_SHORT_TEXT} characters."
            }

            form.callReasons.values.any { it.isNotBlank() && !(isPlainText(it, ClaimRules.MAX_SHORT_TEXT) && '\n' !in it) } -> {
                "A call reason must be at most ${ClaimRules.MAX_SHORT_TEXT} characters on one line."
            }

            else -> {
                null
            }
        }

    // The business behind [e164] if it is approved.
    private fun JdbcTransaction.businessOf(e164: String): UUID? =
        BusinessNumbers
            .join(Businesses, JoinType.INNER, BusinessNumbers.businessId, Businesses.id)
            .select(Businesses.id)
            .where { (BusinessNumbers.e164 eq e164) and (Businesses.status eq ReviewStatus.APPROVED) }
            .singleOrNull()
            ?.get(Businesses.id)

    private fun randomToken(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(TOKEN_BYTES).also(random::nextBytes))

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    /** Limits. */
    companion object {
        /** Hours a session lasts. */
        const val SESSION_HOURS = 12L
        private const val TOKEN_BYTES = 32
        private const val MAX_TOKEN = 64
        private const val TOO_MANY_CODES = "Too many codes requested"
        private val START_PER_NUMBER = RateLimit("portal_start_number", maxHits = 3, window = 1.hours)
        private val START_PER_IP = RateLimit("portal_start_ip", maxHits = 10, window = 1.hours)
        private val log = LoggerFactory.getLogger(PortalService::class.java)

        private fun numberOf(raw: String): E164 = E164.parseLenient(raw) ?: throw invalidNumber()

        private fun hash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).toHexString()

        private fun isWebsite(url: String) =
            url.length <= ClaimRules.MAX_SHORT_TEXT && url.startsWith("https://") && url.length > "https://".length &&
                url.none { it.isWhitespace() || it.isISOControl() || it == '"' || it == '<' || it == '>' }
    }
}
