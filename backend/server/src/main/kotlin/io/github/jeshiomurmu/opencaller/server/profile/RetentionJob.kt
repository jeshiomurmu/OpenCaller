package io.github.jeshiomurmu.opencaller.server.profile

import io.github.jeshiomurmu.opencaller.server.business.ClaimStatus
import io.github.jeshiomurmu.opencaller.server.db.BusinessClaimDocuments
import io.github.jeshiomurmu.opencaller.server.db.BusinessClaims
import io.github.jeshiomurmu.opencaller.server.db.Disputes
import io.github.jeshiomurmu.opencaller.server.db.NumberReputation
import io.github.jeshiomurmu.opencaller.server.db.ProfileViews
import io.github.jeshiomurmu.opencaller.server.db.Reports
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStore
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStoreException
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** What one retention run did. */
@Serializable
data class RetentionResult(
    val accountsPurged: Int,
    val reportsDeleted: Int,
    val visitsExpired: Int,
    val claimFilesDeleted: Int = 0,
)

/**
 * Daily clean-up (docs/architecture.md §7 "Data retention", docs/prd.md F-SO-07):
 * 1. purges accounts deleted with `DELETE /v1/me`: the reports the user wrote (their numbers are queued for
 *    rescoring), the profile picture and the user row with everything that cascades from it (devices, tokens,
 *    reporter trust, subscriptions, family groups); disputes they opened stay, without the claimant;
 * 2. deletes profile views and searches older than 90 days;
 * 3. deletes the files of business claims decided more than 90 days ago (an approved business keeps its logo).
 * A run happens daily, so a deleted account is gone within a day, well inside the 30 days the PRD allows.
 */
class RetentionJob(
    private val db: ServerDatabase,
    private val objects: ObjectStore,
    private val clock: Clock,
) {
    /** Runs once (the caller holds the leader lock). */
    suspend fun run(): RetentionResult {
        val deleted =
            db.query {
                Users
                    .select(Users.id, Users.avatarKey)
                    .where { Users.deletedAt.isNotNull() }
                    .limit(PURGE_BATCH)
                    .map { it[Users.id] to it[Users.avatarKey] }
            }
        var purged = 0
        var reports = 0
        deleted.forEach { (id, avatar) ->
            val claimFiles = db.query { claimFilesOf(BusinessClaims.userId eq id) }
            val files = listOfNotNull(avatar) + claimFiles.map { it.second }
            // A file that can't be deleted now keeps its account for the next run, so nothing is left behind.
            if (deleteFiles(files) == files.size) {
                reports += db.query { purge(id) }
                purged++
            }
        }
        val expired =
            db.query {
                ProfileViews.deleteWhere { ProfileViews.at less now().minusDays(ProfileService.VISIT_DAYS) }
            }
        return RetentionResult(purged, reports, expired, expireClaimFiles())
    }

    // Files of claims decided more than 90 days ago; a file that can't be deleted now keeps its row for the next run.
    private suspend fun expireClaimFiles(): Int {
        val decided = ClaimStatus.entries.filterNot { it.isOpen }.map { it.value }
        val files =
            db.query {
                claimFilesOf((BusinessClaims.status inList decided) and (BusinessClaims.decidedAt less now().minusDays(CLAIM_FILE_DAYS)))
            }
        var deleted = 0
        files.forEach { (claim, key) ->
            if (deleteFiles(listOf(key)) == 1) {
                db.query { BusinessClaimDocuments.deleteWhere { (claimId eq claim) and (objectKey eq key) } }
                deleted++
            }
        }
        return deleted
    }

    private fun JdbcTransaction.claimFilesOf(where: Op<Boolean>): List<Pair<UUID, String>> =
        BusinessClaimDocuments
            .join(BusinessClaims, JoinType.INNER, BusinessClaimDocuments.claimId, BusinessClaims.id)
            .select(BusinessClaimDocuments.claimId, BusinessClaimDocuments.objectKey)
            .where(where)
            .limit(PURGE_BATCH)
            .map { it[BusinessClaimDocuments.claimId] to it[BusinessClaimDocuments.objectKey] }

    // Returns how many of [keys] are gone; failures are logged and retried next run.
    private suspend fun deleteFiles(keys: List<String>): Int =
        keys.count { key ->
            try {
                objects.delete(key)
                true
            } catch (e: ObjectStoreException) {
                log.warn("Could not delete a stored file (picture or claim file); trying again next run", e)
                false
            }
        }

    // Returns the number of reports deleted.
    private fun JdbcTransaction.purge(userId: UUID): Int {
        val reported =
            Reports
                .select(Reports.e164)
                .where { Reports.reporterId eq userId }
                .withDistinct()
                .map { it[Reports.e164] }
        val deleted = Reports.deleteWhere { reporterId eq userId }
        // The scoring job rescores the oldest-scored numbers first; this puts these at the front of that queue.
        reported.chunked(CHUNK).forEach { numbers ->
            NumberReputation.update({ NumberReputation.e164 inList numbers }) { it[updatedAt] = RESCORE_SOON }
        }
        Disputes.update({ Disputes.claimantUserId eq userId }) { it[claimantUserId] = null }
        Users.deleteWhere { id eq userId }
        return deleted
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    /** Job name and batch sizes. */
    companion object {
        /** Name of the job (`POST /admin/jobs/retention`). */
        const val JOB_NAME = "retention"

        /** Hour (UTC) of the daily run. */
        const val HOUR_UTC = 3

        private const val PURGE_BATCH = 500
        private const val CLAIM_FILE_DAYS = 90L
        private const val CHUNK = 1_000
        private val RESCORE_SOON = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        private val log = LoggerFactory.getLogger(RetentionJob::class.java)
    }
}
