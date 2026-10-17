package io.github.jeshiomurmu.opencaller.server.testing

import io.github.jeshiomurmu.opencaller.server.db.BusinessNumbers
import io.github.jeshiomurmu.opencaller.server.db.Businesses
import io.github.jeshiomurmu.opencaller.server.db.ReportLabel
import io.github.jeshiomurmu.opencaller.server.db.ReporterTrust
import io.github.jeshiomurmu.opencaller.server.db.Reports
import io.github.jeshiomurmu.opencaller.server.db.ReviewStatus
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.db.Visibility
import io.github.jeshiomurmu.opencaller.server.lookup.ensureIndexed
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

// Direct database fixtures for tests (profiles, businesses and reports have no write API until later phases).

/** Inserts a user with [name] and [visibility] (indexed for lookups, like a real sign-in) and returns its id. */
suspend fun insertUser(
    e164: String,
    name: String? = null,
    visibility: Visibility = Visibility.REGISTERED_ONLY,
    incognito: Boolean = false,
    at: Instant = Instant.parse("2026-10-01T00:00:00Z"),
): UUID {
    val id = UUID.randomUUID()
    TestInfra.database.query {
        Users.insert {
            it[Users.id] = id
            it[Users.e164] = e164
            it[verifiedAt] = at.atOffset(ZoneOffset.UTC)
            it[displayName] = name
            it[Users.visibility] = visibility
            it[Users.incognito] = incognito
        }
        ensureIndexed(listOf(e164))
    }
    return id
}

/** Changes the profile of the user with [e164]. */
suspend fun updateProfile(
    e164: String,
    name: String? = null,
    visibility: Visibility? = null,
    incognito: Boolean? = null,
) {
    TestInfra.database.query {
        Users.update({ Users.e164 eq e164 }) { row ->
            name?.let { row[displayName] = it }
            visibility?.let { row[Users.visibility] = it }
            incognito?.let { row[Users.incognito] = it }
        }
    }
}

/** Inserts a business owning [e164] with [status] (APPROVED = verified) and returns its id. */
suspend fun insertBusiness(
    e164: String,
    name: String,
    status: ReviewStatus = ReviewStatus.APPROVED,
    category: String? = "bank",
): UUID {
    val id = UUID.randomUUID()
    TestInfra.database.query {
        Businesses.insert {
            it[Businesses.id] = id
            it[legalName] = "$name Ltd"
            it[displayName] = name
            it[Businesses.category] = category
            it[countryIso] = "BD"
            it[Businesses.status] = status
        }
        BusinessNumbers.insert {
            it[BusinessNumbers.e164] = e164
            it[businessId] = id
            it[claimedVia] = "test"
            it[defaultCallReason] = "Account security"
        }
    }
    return id
}

// Reporter accounts get numbers from +1 999 000 0000 up (area code 999 is unassigned, so no test number collides).
private val nextReporter = AtomicLong(19_990_000_000L)

/** Inserts [count] spam reports on [e164] at [at], each from a new reporter with [trust]; returns the reporter ids. */
suspend fun insertReports(
    e164: String,
    count: Int,
    at: Instant = DEFAULT_REPORT_TIME,
    trust: Float = 0.5f,
    category: String? = "scam",
): List<UUID> = insertLabelled(e164, count, ReportLabel.SPAM, at, trust, category)

/** Inserts [count] not-spam reports on [e164] at [at], each from a new reporter with [trust]. */
suspend fun insertNotSpamReports(
    e164: String,
    count: Int,
    at: Instant = DEFAULT_REPORT_TIME,
    trust: Float = 0.5f,
): List<UUID> = insertLabelled(e164, count, ReportLabel.NOT_SPAM, at, trust, category = null)

private val DEFAULT_REPORT_TIME = Instant.parse("2026-10-16T12:00:00Z")

@Suppress("LongParameterList") // private helper behind the two functions above
private suspend fun insertLabelled(
    e164: String,
    count: Int,
    label: ReportLabel,
    at: Instant,
    trust: Float,
    category: String?,
): List<UUID> =
    List(count) {
        val reporter = insertUser("+${nextReporter.getAndIncrement()}")
        TestInfra.database.query {
            ReporterTrust.upsert {
                it[userId] = reporter
                it[ReporterTrust.trust] = trust
            }
            Reports.insert {
                it[Reports.e164] = e164
                it[reporterId] = reporter
                it[Reports.label] = label
                it[Reports.category] = category
                it[createdAt] = at.atOffset(ZoneOffset.UTC)
            }
        }
        reporter
    }
