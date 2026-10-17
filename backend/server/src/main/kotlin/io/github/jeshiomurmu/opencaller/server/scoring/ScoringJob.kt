package io.github.jeshiomurmu.opencaller.server.scoring

import io.github.jeshiomurmu.opencaller.server.config.ScoringConfig
import io.github.jeshiomurmu.opencaller.server.db.BusinessNumbers
import io.github.jeshiomurmu.opencaller.server.db.Businesses
import io.github.jeshiomurmu.opencaller.server.db.DEFAULT_REPORTER_TRUST
import io.github.jeshiomurmu.opencaller.server.db.Disputes
import io.github.jeshiomurmu.opencaller.server.db.JobState
import io.github.jeshiomurmu.opencaller.server.db.NumberReputation
import io.github.jeshiomurmu.opencaller.server.db.ReporterTrust
import io.github.jeshiomurmu.opencaller.server.db.Reports
import io.github.jeshiomurmu.opencaller.server.db.ReviewStatus
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.UnlistRequests
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.lookup.NumberKey
import io.github.jeshiomurmu.opencaller.server.lookup.ensureIndexed
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.max
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** What one scoring run did. */
@Serializable
data class ScoringResult(
    val rescored: Int,
    val capped: Int,
    val disputesOpened: Int,
    val unlistProcessed: Int,
    val indexed: Int,
)

/**
 * The scoring job (every 15 minutes, docs/architecture.md §9). Each run:
 * 1. adds users and business numbers missing from the lookup index,
 * 2. rescores every number with reports newer than the last run (minus [OVERLAP_SECONDS], since a report's
 *    transaction may commit slightly after its timestamp) and up to [DECAY_BATCH] numbers not rescored for a day
 *    (so time decay shows up),
 * 3. caps verified business numbers at [ScoringConfig.verifiedCap] and opens one dispute-queue item instead,
 * 4. processes verified unlist requests, which hides the number's name from lookups and search.
 */
class ScoringJob(
    private val db: ServerDatabase,
    private val config: ScoringConfig,
    private val clock: Clock,
) {
    private val scorer = BayesianScorer(config)

    /** Runs once (the caller holds the leader lock). */
    suspend fun run(): ScoringResult {
        val now = clock.instant()
        val indexed = db.query { indexMissingNumbers() }
        val (dirty, newWatermark) = db.query { dirtyNumbers(now) }
        var capped = 0
        var opened = 0
        dirty.chunked(BATCH_SIZE).forEach { batch ->
            val outcome = db.query { rescore(batch, now) }
            capped += outcome.first
            opened += outcome.second
        }
        if (newWatermark != null) db.query { saveWatermark(newWatermark, now) }
        val unlisted = db.query { processUnlistRequests(now) }
        return ScoringResult(dirty.size, capped, opened, unlisted, indexed)
    }

    private fun JdbcTransaction.indexMissingNumbers(): Int {
        val missingUsers =
            Users
                .join(NumberReputation, JoinType.LEFT, Users.e164, NumberReputation.e164)
                .select(Users.e164)
                .where { NumberReputation.e164.isNull() and Users.deletedAt.isNull() }
                .limit(INDEX_BATCH)
                .map { it[Users.e164] }
        val missingBusinesses =
            BusinessNumbers
                .join(NumberReputation, JoinType.LEFT, BusinessNumbers.e164, NumberReputation.e164)
                .select(BusinessNumbers.e164)
                .where { NumberReputation.e164.isNull() }
                .limit(INDEX_BATCH)
                .map { it[BusinessNumbers.e164] }
        val missing = (missingUsers + missingBusinesses).distinct()
        ensureIndexed(missing)
        return missing.size
    }

    private fun JdbcTransaction.dirtyNumbers(now: Instant): Pair<List<String>, Instant?> {
        val watermark =
            JobState
                .selectAll()
                .where { JobState.name eq JOB_NAME }
                .singleOrNull()
                ?.get(JobState.watermark)
        val since = watermark?.minusSeconds(OVERLAP_SECONDS) ?: EPOCH
        val newest = Reports.createdAt.max()
        val maxSeen = Reports.select(newest).where { Reports.createdAt greater since }.single()[newest]
        val reported =
            Reports
                .select(Reports.e164)
                .where { Reports.createdAt greater since }
                .withDistinct()
                .map { it[Reports.e164] }
        val stale =
            NumberReputation
                .select(NumberReputation.e164)
                .where {
                    (NumberReputation.updatedAt less utc(now).minusDays(1)) and
                        ((NumberReputation.reportCount greater 0) or (NumberReputation.notSpamCount greater 0))
                }.orderBy(NumberReputation.updatedAt)
                .limit(DECAY_BATCH)
                .map { it[NumberReputation.e164] }
        return (reported + stale).distinct() to maxSeen?.toInstant()
    }

    // Returns (numbers capped, disputes opened).
    private fun JdbcTransaction.rescore(
        numbers: List<String>,
        now: Instant,
    ): Pair<Int, Int> {
        val reports =
            Reports
                .join(ReporterTrust, JoinType.LEFT, Reports.reporterId, ReporterTrust.userId)
                .select(
                    Reports.id,
                    Reports.e164,
                    Reports.reporterId,
                    Reports.label,
                    Reports.category,
                    Reports.createdAt,
                    ReporterTrust.trust,
                ).where { Reports.e164 inList numbers }
                .groupBy({ it[Reports.e164] }) { row ->
                    ScoredReport(
                        id = row[Reports.id],
                        reporterId = row[Reports.reporterId],
                        label = row[Reports.label],
                        trust = (row.getOrNull(ReporterTrust.trust) ?: DEFAULT_REPORTER_TRUST).toDouble(),
                        at = row[Reports.createdAt].toInstant(),
                        category = row[Reports.category],
                    )
                }
        val verified = verifiedBusinessNumbers(numbers)
        val scored = numbers.map { it to scorer.score(reports[it].orEmpty(), now) }
        var capped = 0
        var opened = 0
        val rows =
            scored.map { (number, reputation) ->
                val overCap = number in verified && reputation.score > config.verifiedCap
                if (overCap) {
                    capped++
                    if (openReviewItem(number, now)) opened++
                }
                number to (if (overCap) reputation.copy(score = config.verifiedCap) else reputation)
            }
        writeReputations(rows, now)
        return capped to opened
    }

    private fun JdbcTransaction.verifiedBusinessNumbers(numbers: List<String>): Set<String> =
        BusinessNumbers
            .join(Businesses, JoinType.INNER, BusinessNumbers.businessId, Businesses.id)
            .select(BusinessNumbers.e164)
            .where { (BusinessNumbers.e164 inList numbers) and (Businesses.status eq ReviewStatus.APPROVED) }
            .map { it[BusinessNumbers.e164] }
            .toSet()

    /** Opens a pending dispute for human review unless one from this job is already open. */
    private fun JdbcTransaction.openReviewItem(
        number: String,
        now: Instant,
    ): Boolean {
        val open =
            Disputes
                .selectAll()
                .where {
                    (Disputes.e164 eq number) and (Disputes.status eq ReviewStatus.PENDING) and (Disputes.reason eq AUTO_DISPUTE_REASON)
                }.any()
        if (open) return false
        Disputes.insert {
            it[e164] = number
            it[reason] = AUTO_DISPUTE_REASON
            it[createdAt] = utc(now)
        }
        return true
    }

    private fun JdbcTransaction.writeReputations(
        rows: List<Pair<String, Reputation>>,
        now: Instant,
    ) {
        NumberReputation.batchUpsert(
            rows,
            onUpdateExclude = listOf(NumberReputation.countryCode, NumberReputation.bucket16, NumberReputation.lineType),
            shouldReturnGeneratedValues = false,
        ) { (number, reputation) ->
            val key = NumberKey.of(number)
            this[NumberReputation.e164] = number
            this[NumberReputation.countryCode] = key.countryCode.toShort()
            this[NumberReputation.bucket16] = key.bucket16
            this[NumberReputation.spamScore] = reputation.score.toFloat()
            this[NumberReputation.category] = reputation.category
            this[NumberReputation.reportCount] = reputation.spamCount
            this[NumberReputation.notSpamCount] = reputation.notSpamCount
            this[NumberReputation.firstSeen] = utc(reputation.firstReportAt ?: now)
            this[NumberReputation.lastReportAt] = reputation.lastReportAt?.let(::utc)
            this[NumberReputation.modelVersion] = MODEL_VERSION
            this[NumberReputation.updatedAt] = utc(now)
        }
    }

    private fun JdbcTransaction.saveWatermark(
        watermark: Instant,
        now: Instant,
    ) {
        JobState.upsert {
            it[name] = JOB_NAME
            it[JobState.watermark] = utc(watermark)
            it[updatedAt] = utc(now)
        }
    }

    private fun JdbcTransaction.processUnlistRequests(now: Instant): Int =
        UnlistRequests.update({ UnlistRequests.verifiedAt.isNotNull() and UnlistRequests.processedAt.isNull() }) {
            it[processedAt] = utc(now)
        }

    private fun utc(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

    /** Job name, model version and batch sizes. */
    companion object {
        /** `job_state.name` and the advisory-lock name of this job. */
        const val JOB_NAME = "scoring"

        /** `number_reputation.model_version` written by this job. */
        const val MODEL_VERSION = "bayes-v1"

        /** `disputes.reason` of review items this job opens for verified businesses. */
        const val AUTO_DISPUTE_REASON = "auto: verified business number reached the spam score cap"

        private const val OVERLAP_SECONDS = 300L
        private const val BATCH_SIZE = 500
        private const val DECAY_BATCH = 20_000
        private const val INDEX_BATCH = 10_000
        private val EPOCH = OffsetDateTime.parse("1970-01-01T00:00:00Z")
    }
}
