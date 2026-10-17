package io.github.jeshiomurmu.opencaller.server.lookup

import io.github.jeshiomurmu.opencaller.server.db.BusinessNumbers
import io.github.jeshiomurmu.opencaller.server.db.Businesses
import io.github.jeshiomurmu.opencaller.server.db.NumberReputation
import io.github.jeshiomurmu.opencaller.server.db.ReviewStatus
import io.github.jeshiomurmu.opencaller.server.db.UnlistRequests
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.db.Visibility
import io.github.jeshiomurmu.opencaller.server.scoring.SpamLabels
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select

/** A verified business behind a number. */
@Serializable
data class BusinessInfo(
    val name: String,
    val category: String? = null,
    val verified: Boolean = true,
    val callReason: String? = null,
)

/**
 * What the server may say about a number: a name (only if the profile's visibility allows it and the number is
 * not unlisted), community spam data (only if it has reports) and a verified business.
 */
@Serializable
data class NumberInfo(
    val name: String? = null,
    val spamScore: Double? = null,
    val label: String? = null,
    val category: String? = null,
    val reportCount: Int = 0,
    val notSpamCount: Int = 0,
    val lineType: String? = null,
    val business: BusinessInfo? = null,
) {
    /** `true` if there is nothing to tell (such numbers are left out of buckets). */
    val isEmpty: Boolean get() = name == null && spamScore == null && business == null && lineType == null
}

/**
 * Reads [NumberInfo] for lookups and search, applying the name rules of docs/architecture.md §4.7: `everyone`
 * names always, `registered_only` names only for [authenticated] callers, `nobody` never, and no name at all for
 * numbers whose unlist request has been processed (docs/prd.md F-PR-17). Spam data is independent of the profile.
 */
object NumberDirectory {
    private val columns =
        listOf(
            NumberReputation.e164,
            NumberReputation.spamScore,
            NumberReputation.category,
            NumberReputation.reportCount,
            NumberReputation.notSpamCount,
            NumberReputation.lineType,
            Users.id,
            Users.displayName,
            Users.visibility,
            Users.deletedAt,
            Businesses.displayName,
            Businesses.category,
            Businesses.status,
            BusinessNumbers.defaultCallReason,
        )

    /** Info for every indexed number matching [where] (a condition on `number_reputation`), keyed by E.164. */
    fun JdbcTransaction.infos(
        where: Op<Boolean>,
        authenticated: Boolean,
    ): Map<String, NumberInfo> {
        val rows =
            NumberReputation
                .join(Users, JoinType.LEFT, NumberReputation.e164, Users.e164)
                .join(BusinessNumbers, JoinType.LEFT, NumberReputation.e164, BusinessNumbers.e164)
                .join(Businesses, JoinType.LEFT, BusinessNumbers.businessId, Businesses.id)
                .select(columns)
                .where(where)
                .toList()
        if (rows.isEmpty()) return emptyMap()
        val numbers = rows.map { it[NumberReputation.e164] }
        val unlisted =
            UnlistRequests
                .select(UnlistRequests.e164)
                .where { (UnlistRequests.e164 inList numbers) and UnlistRequests.processedAt.isNotNull() }
                .map { it[UnlistRequests.e164] }
                .toSet()
        return rows.associate { row ->
            val number = row[NumberReputation.e164]
            number to toInfo(row, listed = number !in unlisted, authenticated = authenticated)
        }
    }

    private fun toInfo(
        row: ResultRow,
        listed: Boolean,
        authenticated: Boolean,
    ): NumberInfo {
        val reports = row[NumberReputation.reportCount]
        val notSpam = row[NumberReputation.notSpamCount]
        val hasReports = reports + notSpam > 0
        val score = row[NumberReputation.spamScore].toDouble().takeIf { hasReports }
        return NumberInfo(
            name = if (listed) visibleName(row, authenticated) else null,
            spamScore = score?.let { Math.round(it * SCORE_SCALE) / SCORE_SCALE },
            label = score?.let(SpamLabels::of),
            category = row[NumberReputation.category].takeIf { hasReports },
            reportCount = reports,
            notSpamCount = notSpam,
            lineType = row[NumberReputation.lineType],
            business = if (listed) business(row) else null,
        )
    }

    private fun visibleName(
        row: ResultRow,
        authenticated: Boolean,
    ): String? {
        if (row.getOrNull(Users.id) == null || row.getOrNull(Users.deletedAt) != null) return null
        val allowed =
            when (row.getOrNull(Users.visibility)) {
                Visibility.EVERYONE -> true
                Visibility.REGISTERED_ONLY -> authenticated
                Visibility.NOBODY, null -> false
            }
        return if (allowed) row.getOrNull(Users.displayName) else null
    }

    private fun business(row: ResultRow): BusinessInfo? =
        row
            .getOrNull(Businesses.displayName)
            ?.takeIf { row.getOrNull(Businesses.status) == ReviewStatus.APPROVED }
            ?.let { name ->
                BusinessInfo(name, row.getOrNull(Businesses.category), verified = true, row.getOrNull(BusinessNumbers.defaultCallReason))
            }

    // Scores are REAL in the database; three decimals are plenty and avoid float noise like 0.4285714328289032.
    private const val SCORE_SCALE = 1000.0
}
