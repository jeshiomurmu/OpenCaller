package io.github.jeshiomurmu.opencaller.server.reports

import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.db.ReportLabel
import io.github.jeshiomurmu.opencaller.server.db.ReporterTrust
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.invalidNumber
import io.github.jeshiomurmu.opencaller.server.http.isPlainText
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import io.github.jeshiomurmu.opencaller.server.scoring.REPORT_CATEGORIES
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.time.Duration.Companion.days
import io.github.jeshiomurmu.opencaller.server.db.Reports as ReportsTable

/** One report: the number, `spam` or `not_spam`, an optional category (spam only) and comment (docs/architecture.md §10.4). */
@Serializable
data class ReportItem(
    val e164: String,
    val label: String,
    val category: String? = null,
    val comment: String? = null,
)

/** `POST /v1/reports` body: 1 to 50 reports. */
@Serializable
data class ReportBatch(
    val reports: List<ReportItem>,
)

/** `POST /v1/reports` response. */
@Serializable
data class ReportBatchResponse(
    val accepted: Int,
)

/**
 * Stores reports for the scoring job (docs/architecture.md §4.8). Every number is validated with libphonenumber;
 * a batch is all-or-nothing. A reporter's first report gives them the default trust 0.5 (`reporter_trust`).
 */
class ReportService(
    private val db: ServerDatabase,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    /** Validates and stores [batch] from [reporterId]. */
    suspend fun submit(
        reporterId: UUID,
        batch: ReportBatch,
    ): ReportBatchResponse {
        if (batch.reports.isEmpty() || batch.reports.size > MAX_BATCH) {
            throw ApiProblem(HttpStatusCode.BadRequest, "invalid_request", "Send 1 to $MAX_BATCH reports per request")
        }
        val valid = batch.reports.mapIndexed { index, item -> validate(index, item) }
        enforce(rateLimiter.hit(PER_DAY, reporterId.toString(), cost = valid.size), "Too many reports today")
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        db.query {
            ReporterTrust.insertIgnore { it[userId] = reporterId }
            ReportsTable.batchInsert(valid, shouldReturnGeneratedValues = false) { report ->
                this[ReportsTable.e164] = report.number.value
                this[ReportsTable.reporterId] = reporterId
                this[ReportsTable.label] = report.label
                this[ReportsTable.category] = report.category
                this[ReportsTable.comment] = report.comment
                this[ReportsTable.createdAt] = now
            }
        }
        return ReportBatchResponse(accepted = valid.size)
    }

    private class ValidReport(
        val number: E164,
        val label: ReportLabel,
        val category: String?,
        val comment: String?,
    )

    private fun validate(
        index: Int,
        item: ReportItem,
    ): ValidReport {
        val number = E164.parseOrNull(item.e164) ?: throw invalidNumber("reports[$index].e164 must be a valid number in E.164 format.")
        val label =
            ReportLabel.entries.firstOrNull { it.sqlValue == item.label } ?: invalid("reports[$index].label must be spam or not_spam")
        requireValid(item.category == null || isSpamCategory(label, item.category)) {
            invalidReport("reports[$index].category must be one of ${REPORT_CATEGORIES.sorted().joinToString()} (spam reports only)")
        }
        val comment = item.comment?.trim()?.ifEmpty { null }
        requireValid(comment == null || isPlainText(comment, MAX_COMMENT)) {
            invalidReport("reports[$index].comment must be at most $MAX_COMMENT characters of text")
        }
        return ValidReport(number, label, item.category, comment)
    }

    private fun isSpamCategory(
        label: ReportLabel,
        category: String,
    ) = label == ReportLabel.SPAM && category in REPORT_CATEGORIES

    private fun invalid(detail: String): Nothing = throw invalidReport(detail)

    private fun invalidReport(detail: String) = ApiProblem(HttpStatusCode.BadRequest, "invalid_request", "Invalid report", detail)

    /** Limits (docs/rules.md §7). */
    companion object {
        /** Reports per request. */
        const val MAX_BATCH = 50

        /** Longest comment. */
        const val MAX_COMMENT = 280

        /** docs/rules.md §7: reports 100/day per user. */
        val PER_DAY = RateLimit("reports_user", maxHits = 100, window = 1.days)
    }
}
