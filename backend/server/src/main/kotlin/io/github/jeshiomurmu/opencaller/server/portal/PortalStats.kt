package io.github.jeshiomurmu.opencaller.server.portal

import io.github.jeshiomurmu.opencaller.server.db.BusinessNumbers
import io.github.jeshiomurmu.opencaller.server.db.BusinessSearchCounts
import io.github.jeshiomurmu.opencaller.server.db.Businesses
import io.github.jeshiomurmu.opencaller.server.db.Disputes
import io.github.jeshiomurmu.opencaller.server.db.ReportLabel
import io.github.jeshiomurmu.opencaller.server.db.Reports
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** One of the business's numbers and the reason callers see for it. */
data class PortalNumber(
    val e164: String,
    val callReason: String?,
)

/** One number's counts for one day (UTC): explicit searches, spam reports and not-spam reports. */
data class PortalDay(
    val day: LocalDate,
    val e164: String,
    val searches: Int,
    val spamReports: Int,
    val notSpamReports: Int,
)

/** A dispute about one of the business's numbers: when it was filed, where it stands, when it was decided. */
data class PortalDispute(
    val e164: String,
    val filedOn: LocalDate,
    val status: String,
    val decidedOn: LocalDate?,
)

/** What the dashboard shows. */
data class PortalDashboard(
    val name: String,
    val category: String?,
    val website: String?,
    val hours: String?,
    val numbers: List<PortalNumber>,
    val days: List<PortalDay>,
    val disputes: List<PortalDispute>,
)

/**
 * The dashboard's figures, all from data the server already holds for its own purposes (ADR 0008): searches of the
 * numbers (counted without the searcher), spam and not-spam reports, and disputes. Reports and disputes are counted
 * and listed; who wrote them and what they wrote stays hidden.
 */
internal object PortalStats {
    /** Days of counts shown. */
    const val DAYS = 30L
    private const val MAX_DISPUTES = 50

    /** The dashboard of business [businessId]. */
    fun JdbcTransaction.dashboardOf(
        businessId: UUID,
        clock: Clock,
    ): PortalDashboard {
        val business = Businesses.selectAll().where { Businesses.id eq businessId }.single()
        val numbers =
            BusinessNumbers
                .selectAll()
                .where { BusinessNumbers.businessId eq businessId }
                .orderBy(BusinessNumbers.e164)
                .map { PortalNumber(it[BusinessNumbers.e164], it[BusinessNumbers.defaultCallReason]) }
        val e164s = numbers.map { it.e164 }
        val since = LocalDate.now(clock).minusDays(DAYS - 1)
        return PortalDashboard(
            name = business[Businesses.displayName],
            category = business[Businesses.category],
            website = business[Businesses.website],
            hours = business[Businesses.hours],
            numbers = numbers,
            days = if (e164s.isEmpty()) emptyList() else days(e164s, since),
            disputes = if (e164s.isEmpty()) emptyList() else disputes(e164s),
        )
    }

    private fun JdbcTransaction.days(
        e164s: List<String>,
        since: LocalDate,
    ): List<PortalDay> {
        val searches =
            BusinessSearchCounts
                .selectAll()
                .where { (BusinessSearchCounts.e164 inList e164s) and (BusinessSearchCounts.day greaterEq since) }
                .associate { (it[BusinessSearchCounts.day] to it[BusinessSearchCounts.e164]) to it[BusinessSearchCounts.searches] }
        val day = Reports.createdAt.date()
        val count = Reports.id.count()
        val reports =
            Reports
                .select(Reports.e164, day, Reports.label, count)
                .where { (Reports.e164 inList e164s) and (Reports.createdAt greaterEq since.atStartOfDay().atOffset(ZoneOffset.UTC)) }
                .groupBy(Reports.e164, day, Reports.label)
                .map { Triple(it[day] to it[Reports.e164], it[Reports.label], it[count].toInt()) }
        val keys =
            (
                searches.keys +
                    reports.map {
                        it.first
                    }
            ).toSortedSet(compareByDescending<Pair<LocalDate, String>> { it.first }.thenBy { it.second })
        return keys.map { key ->
            PortalDay(
                day = key.first,
                e164 = key.second,
                searches = searches[key] ?: 0,
                spamReports = reports.filter { it.first == key && it.second == ReportLabel.SPAM }.sumOf { it.third },
                notSpamReports = reports.filter { it.first == key && it.second == ReportLabel.NOT_SPAM }.sumOf { it.third },
            )
        }
    }

    private fun JdbcTransaction.disputes(e164s: List<String>): List<PortalDispute> =
        Disputes
            .select(Disputes.e164, Disputes.createdAt, Disputes.status, Disputes.resolvedAt)
            .where { Disputes.e164 inList e164s }
            .orderBy(Disputes.createdAt, SortOrder.DESC)
            .limit(MAX_DISPUTES)
            .map {
                PortalDispute(
                    e164 = it[Disputes.e164],
                    filedOn = it[Disputes.createdAt].withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(),
                    status = it[Disputes.status].sqlValue,
                    decidedOn = it[Disputes.resolvedAt]?.withOffsetSameInstant(ZoneOffset.UTC)?.toLocalDate(),
                )
            }
}
