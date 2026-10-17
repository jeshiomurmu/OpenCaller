package io.github.jeshiomurmu.opencaller.server.scoring

import io.github.jeshiomurmu.opencaller.server.config.ScoringConfig
import io.github.jeshiomurmu.opencaller.server.db.ReportLabel
import io.github.jeshiomurmu.opencaller.server.db.ReportLabel.NOT_SPAM
import io.github.jeshiomurmu.opencaller.server.db.ReportLabel.SPAM
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * TC-API-005: the §9 posterior on hand-computed fixtures, ±1e-6. Defaults α0 = 1, β0 = 2, half-life 30 days, so a
 * report's weight is trust · 2^(−age/30 d):
 *
 *     no reports                          1 / 3                         = 0.333333
 *     spam, trust 0.5, now                (1 + 0.5) / (3 + 0.5)         = 0.428571
 *     spam, trust 1.0, 30 d old (w 0.5)   (1 + 0.5) / (3 + 0.5)         = 0.428571
 *     not spam, trust 1.0, now            1 / (3 + 1)                   = 0.25
 *     spam 0.5 now + spam 0.8 60 d (w 0.2) + not spam 1.0 30 d (w 0.5)
 *                                         (1 + 0.5 + 0.2) / (3 + 1.2)   = 0.404762
 *     spam, trust 1.0, 15 d (w 2^-0.5)    1.707107 / 3.707107           = 0.460496
 *     10 spam, trust 1.0, now             11 / 13                       = 0.846154
 *     20 spam, trust 1.0, now             21 / 23                       = 0.913043
 *     α0 2, β0 8, half-life 7 d; spam 1.0 7 d old (w 0.5)   2.5 / 10.5  = 0.238095
 */
class BayesianScorerTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z")
    private val scorer = BayesianScorer(ScoringConfig())
    private var nextId = 0L

    private fun report(
        label: ReportLabel,
        trust: Double,
        daysOld: Long = 0,
        reporter: UUID? = UUID.randomUUID(),
        category: String? = null,
    ) = ScoredReport(++nextId, reporter, label, trust, now.minus(daysOld, ChronoUnit.DAYS), category)

    private fun assertPosterior(
        expected: Double,
        reports: List<ScoredReport>,
        scorer: BayesianScorer = this.scorer,
    ) = assertEquals(expected, scorer.posterior(reports, now), 1e-6)

    @Test
    fun handComputedFixtures() {
        assertPosterior(0.333333, emptyList())
        assertPosterior(0.428571, listOf(report(SPAM, 0.5)))
        assertPosterior(0.428571, listOf(report(SPAM, 1.0, daysOld = 30)))
        assertPosterior(0.25, listOf(report(NOT_SPAM, 1.0)))
        assertPosterior(0.404762, listOf(report(SPAM, 0.5), report(SPAM, 0.8, daysOld = 60), report(NOT_SPAM, 1.0, daysOld = 30)))
        assertPosterior(0.460496, listOf(report(SPAM, 1.0, daysOld = 15)))
        assertPosterior(0.846154, List(10) { report(SPAM, 1.0) })
        assertPosterior(0.913043, List(20) { report(SPAM, 1.0) })
    }

    @Test
    fun priorsAndHalfLifeAreConfigurable() {
        val custom = BayesianScorer(ScoringConfig(priorSpam = 2.0, priorNotSpam = 8.0, halfLifeDays = 7.0))
        assertPosterior(0.2, emptyList(), custom)
        assertPosterior(0.238095, listOf(report(SPAM, 1.0, daysOld = 7)), custom)
    }

    @Test
    fun reportsFromTheFutureCountAsNew() {
        // Clock skew: a report "1 day in the future" weighs like a fresh one: (1 + 1) / (3 + 1).
        assertPosterior(0.5, listOf(report(SPAM, 1.0, daysOld = -1)))
    }

    @Test
    fun onlyEachReportersLatestReportCounts() {
        val person = UUID.randomUUID()
        val reports =
            listOf(
                report(SPAM, 1.0, daysOld = 2, reporter = person),
                report(SPAM, 1.0, daysOld = 1, reporter = person),
                // Changed their mind: only this one counts for them.
                report(NOT_SPAM, 1.0, reporter = person),
                // Reports whose reporter account was deleted count one by one.
                report(SPAM, 0.5, reporter = null),
                report(SPAM, 0.5, reporter = null),
            )
        val reputation = scorer.score(reports, now)
        // (1 + 0.5 + 0.5) / (3 + 1 + 0.5 + 0.5) = 2 / 5
        assertEquals(0.4, reputation.score, 1e-6)
        assertEquals(2, reputation.spamCount)
        assertEquals(1, reputation.notSpamCount)
        assertEquals(now.minus(0, ChronoUnit.DAYS), reputation.lastReportAt)
    }

    @Test
    fun categoryIsTheMostReportedOneWithTiesGoingToTheNewest() {
        val reports =
            listOf(
                report(SPAM, 1.0, daysOld = 5, category = "loan"),
                report(SPAM, 1.0, daysOld = 4, category = "scam"),
                report(SPAM, 1.0, daysOld = 3, category = "loan"),
                report(SPAM, 1.0, daysOld = 1, category = "scam"),
                report(NOT_SPAM, 1.0, category = null),
            )
        assertEquals("scam", scorer.score(reports, now).category)
        assertEquals("loan", scorer.score(reports + report(SPAM, 1.0, category = "loan"), now).category)
        assertNull(scorer.score(listOf(report(SPAM, 1.0)), now).category)
    }

    @Test
    fun labelsFollowTheSection9Thresholds() {
        assertNull(SpamLabels.of(0.599))
        assertEquals("suspicious", SpamLabels.of(0.6))
        assertEquals("suspicious", SpamLabels.of(0.849))
        assertEquals("likely_spam", SpamLabels.of(0.85))
    }
}
