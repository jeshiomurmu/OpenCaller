package io.github.jeshiomurmu.opencaller.server.search

import io.github.jeshiomurmu.opencaller.server.db.BusinessNumbers
import io.github.jeshiomurmu.opencaller.server.db.BusinessSearchCounts
import io.github.jeshiomurmu.opencaller.server.db.Businesses
import io.github.jeshiomurmu.opencaller.server.db.ReviewStatus
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.LocalDate

/**
 * Counts explicit searches of verified-business numbers for the business dashboard (docs/prd.md F-BZ-04, owner's
 * decision 2026-11-09): one row per number and day with a count, never who searched. Other numbers are not counted.
 */
internal object BusinessSearchCounter {
    /** Adds one search of [e164] on [day] if it is a number of an approved business. */
    fun JdbcTransaction.countSearch(
        e164: String,
        day: LocalDate,
    ) {
        val verified =
            BusinessNumbers
                .join(Businesses, JoinType.INNER, BusinessNumbers.businessId, Businesses.id)
                .select(BusinessNumbers.e164)
                .where { (BusinessNumbers.e164 eq e164) and (Businesses.status eq ReviewStatus.APPROVED) }
                .any()
        if (!verified) return
        BusinessSearchCounts.upsert(onUpdate = { it[BusinessSearchCounts.searches] = BusinessSearchCounts.searches + 1 }) {
            it[BusinessSearchCounts.e164] = e164
            it[BusinessSearchCounts.day] = day
            it[searches] = 1
        }
    }
}
