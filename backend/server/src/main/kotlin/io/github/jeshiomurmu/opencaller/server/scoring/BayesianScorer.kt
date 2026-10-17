package io.github.jeshiomurmu.opencaller.server.scoring

import io.github.jeshiomurmu.opencaller.server.config.ScoringConfig
import io.github.jeshiomurmu.opencaller.server.db.ReportLabel
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.exp
import kotlin.math.ln

/** One stored report as the scorer sees it; [trust] is the reporter's `reporter_trust` (0.5 by default). */
data class ScoredReport(
    val id: Long,
    val reporterId: UUID?,
    val label: ReportLabel,
    val trust: Double,
    val at: Instant,
    val category: String? = null,
)

/** A number's reputation from its reports: [score] = P(spam | reports); counts are after de-duplication. */
data class Reputation(
    val score: Double,
    val spamCount: Int,
    val notSpamCount: Int,
    val category: String?,
    val firstReportAt: Instant?,
    val lastReportAt: Instant?,
)

/**
 * The Bayesian reputation of docs/architecture.md §9:
 *
 *     P(spam | n) = (α0 + Σ wᵢ·e^(−λ·tᵢ)·sᵢ) / (α0 + β0 + Σ wᵢ·e^(−λ·tᵢ))
 *
 * with sᵢ = 1 for spam and 0 for not-spam, wᵢ the reporter's trust, tᵢ the report's age in days and
 * λ = ln 2 / half-life, so a report counts half as much after one half-life. Only each reporter's latest report on
 * a number counts (one person can't push a number up by reporting it again and again); reports whose reporter
 * account is gone count individually.
 */
class BayesianScorer(
    private val config: ScoringConfig,
) {
    private val lambda = ln(2.0) / config.halfLifeDays

    /** Posterior spam probability of [reports] at [now] (reports are used as given, without de-duplication). */
    fun posterior(
        reports: List<ScoredReport>,
        now: Instant,
    ): Double {
        var spam = 0.0
        var total = 0.0
        reports.forEach { report ->
            val weight = report.trust * exp(-lambda * ageInDays(report.at, now))
            total += weight
            if (report.label == ReportLabel.SPAM) spam += weight
        }
        return (config.priorSpam + spam) / (config.priorSpam + config.priorNotSpam + total)
    }

    /** De-duplicates [reports] per reporter and scores them at [now]. */
    fun score(
        reports: List<ScoredReport>,
        now: Instant,
    ): Reputation {
        val counted = latestPerReporter(reports)
        val spam = counted.filter { it.label == ReportLabel.SPAM }
        return Reputation(
            score = posterior(counted, now),
            spamCount = spam.size,
            notSpamCount = counted.size - spam.size,
            category = topCategory(spam),
            firstReportAt = counted.minOfOrNull { it.at },
            lastReportAt = counted.maxOfOrNull { it.at },
        )
    }

    /** The newest report of each reporter (by time, then id) plus every report without a reporter. */
    fun latestPerReporter(reports: List<ScoredReport>): List<ScoredReport> {
        val (anonymous, known) = reports.partition { it.reporterId == null }
        val latest = known.groupBy { it.reporterId }.values.map { group -> group.maxWith(compareBy({ it.at }, { it.id })) }
        return (anonymous + latest).sortedBy { it.id }
    }

    // Most reported category among spam reports; a tie goes to the category reported most recently.
    private fun topCategory(spam: List<ScoredReport>): String? =
        spam
            .filter { it.category != null }
            .groupBy { it.category!! }
            .maxWithOrNull(compareBy({ it.value.size }, { group -> group.value.maxOf { it.at } }))
            ?.key

    private fun ageInDays(
        at: Instant,
        now: Instant,
    ): Double = Duration.between(at, now).toMillis().coerceAtLeast(0) / MILLIS_PER_DAY

    private companion object {
        const val MILLIS_PER_DAY = 86_400_000.0
    }
}
