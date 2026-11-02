package io.github.jeshiomurmu.opencaller.server.business

import io.github.jeshiomurmu.opencaller.server.db.BusinessClaimDocuments
import io.github.jeshiomurmu.opencaller.server.db.BusinessClaims
import io.github.jeshiomurmu.opencaller.server.db.BusinessNumbers
import io.github.jeshiomurmu.opencaller.server.db.Businesses
import io.github.jeshiomurmu.opencaller.server.db.NumberReputation
import io.github.jeshiomurmu.opencaller.server.db.ReviewStatus
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.lookup.ensureIndexed
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStore
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStoreException
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A claim as the admin web shows it: the full form, its files and its history. */
data class ClaimDetail(
    val id: UUID,
    val e164: String,
    val countryIso: String,
    val legalName: String,
    val displayName: String,
    val category: String,
    val website: String?,
    val hours: String?,
    val callReason: String?,
    val method: String,
    val status: ClaimStatus,
    val logoKey: String?,
    val documents: List<ClaimFile>,
    val createdAt: OffsetDateTime,
    val verifiedAt: OffsetDateTime?,
    val submittedAt: OffsetDateTime?,
    val decidedAt: OffsetDateTime?,
    val reviewer: String?,
    val reviewNote: String?,
    val businessId: UUID?,
)

/** One submitted file of a claim ([sizeBytes] 0 = issued but never submitted). */
data class ClaimFile(
    val key: String,
    val contentType: String,
    val sizeBytes: Long,
)

/** A verified (or revoked) business as the admin web lists it. */
data class BusinessRow(
    val id: UUID,
    val name: String,
    val category: String?,
    val countryIso: String,
    val approved: Boolean,
    val verifiedAt: OffsetDateTime?,
    val numbers: List<String>,
)

/** What an admin decision did, for the audit log and the page. */
sealed interface ReviewOutcome {
    /** Approved: [businessId] now verified for the number. */
    data class Approved(
        val businessId: UUID,
    ) : ReviewOutcome

    /** Rejected with a note to the claimant. */
    data object Rejected : ReviewOutcome

    /** The claim wasn't waiting for review (decided meanwhile) or the number is already a verified business. */
    data class Refused(
        val reason: String,
    ) : ReviewOutcome
}

/**
 * The admin's side of business claims (docs/prd.md F-BZ-01): the review queue, a claim's details and files,
 * approval (which publishes the business: it shows in bucket lookups at once, joins the business directory apps
 * sync, and its number's spam score is capped at the next scoring run, which takes it off the spam lists) and
 * rejection, and revoking a business later.
 */
class ClaimReview(
    private val db: ServerDatabase,
    private val objects: ObjectStore,
    private val clock: Clock,
) {
    /** Claims with [status], oldest submission first (the queue), at most [limit]. */
    suspend fun claims(
        status: ClaimStatus,
        limit: Int = QUEUE_LIMIT,
    ): List<ClaimDetail> =
        db.query {
            BusinessClaims
                .selectAll()
                .where { BusinessClaims.status eq status.value }
                .orderBy(BusinessClaims.submittedAt to SortOrder.ASC_NULLS_LAST, BusinessClaims.createdAt to SortOrder.ASC)
                .limit(limit)
                .map { toDetail(it, files(it[BusinessClaims.id])) }
        }

    /** Claim [id] with its files, or `null`. */
    suspend fun claim(id: UUID): ClaimDetail? =
        db.query {
            BusinessClaims
                .selectAll()
                .where { BusinessClaims.id eq id }
                .singleOrNull()
                ?.let { toDetail(it, files(id)) }
        }

    /** The bytes and type of [key] if it is a submitted file of claim [id] (the admin web never serves other keys). */
    suspend fun file(
        id: UUID,
        key: String,
    ): Pair<ByteArray, String>? {
        val file = claim(id)?.documents?.firstOrNull { it.key == key && it.sizeBytes > 0 } ?: return null
        return objects.get(file.key)?.let { it to file.contentType }
    }

    /** Approves submitted claim [id] by [reviewer]: creates the verified business and publishes it. */
    suspend fun approve(
        id: UUID,
        reviewer: String,
    ): ReviewOutcome =
        db.query {
            val claim =
                BusinessClaims
                    .selectAll()
                    .where { BusinessClaims.id eq id }
                    .forUpdate()
                    .singleOrNull()
            when {
                claim == null || claim[BusinessClaims.status] != ClaimStatus.SUBMITTED.value -> {
                    ReviewOutcome.Refused("The claim isn't waiting for review.")
                }

                isVerifiedBusiness(claim[BusinessClaims.e164]) -> {
                    ReviewOutcome.Refused("The number already belongs to a verified business.")
                }

                else -> {
                    ReviewOutcome.Approved(publish(claim, reviewer))
                }
            }
        }

    /** Rejects submitted claim [id] with [note] (shown to the claimant). */
    suspend fun reject(
        id: UUID,
        reviewer: String,
        note: String,
    ): ReviewOutcome =
        db.query {
            val updated =
                BusinessClaims.update({ (BusinessClaims.id eq id) and (BusinessClaims.status eq ClaimStatus.SUBMITTED.value) }) {
                    it[status] = ClaimStatus.REJECTED.value
                    it[decidedAt] = now()
                    it[BusinessClaims.reviewer] = reviewer
                    it[reviewNote] = note
                }
            if (updated == 1) ReviewOutcome.Rejected else ReviewOutcome.Refused("The claim isn't waiting for review.")
        }

    /**
     * Revokes verified business [businessId]: it leaves bucket lookups at once, and apps drop it from their
     * directory at their next sync ([BusinessDirectory] serves its numbers as removed). Returns `false` if it
     * wasn't verified.
     */
    suspend fun revoke(businessId: UUID): Boolean =
        db.query {
            val now = now()
            val updated =
                Businesses.update({ (Businesses.id eq businessId) and (Businesses.status eq ReviewStatus.APPROVED) }) {
                    it[status] = ReviewStatus.REJECTED
                    it[updatedAt] = now
                }
            BusinessNumbers.update({ BusinessNumbers.businessId eq businessId }) { it[updatedAt] = now }
            updated == 1
        }

    /** Businesses verified at some point (approved and revoked), newest first, with their numbers. */
    suspend fun businesses(limit: Int = QUEUE_LIMIT): List<BusinessRow> =
        db.query {
            Businesses
                .join(BusinessNumbers, JoinType.LEFT, Businesses.id, BusinessNumbers.businessId)
                .selectAll()
                .where { Businesses.verifiedAt.isNotNull() }
                .orderBy(Businesses.verifiedAt to SortOrder.DESC)
                .limit(limit)
                .groupBy { it[Businesses.id] }
                .map { (id, rows) ->
                    val first = rows.first()
                    BusinessRow(
                        id = id,
                        name = first[Businesses.displayName],
                        category = first[Businesses.category],
                        countryIso = first[Businesses.countryIso],
                        approved = first[Businesses.status] == ReviewStatus.APPROVED,
                        verifiedAt = first[Businesses.verifiedAt],
                        numbers = rows.mapNotNull { it.getOrNull(BusinessNumbers.e164) },
                    )
                }
        }

    private fun JdbcTransaction.publish(
        claim: ResultRow,
        reviewer: String,
    ): UUID {
        val now = now()
        val businessId = UUID.randomUUID()
        val number = claim[BusinessClaims.e164]
        Businesses.insert {
            it[id] = businessId
            it[legalName] = claim[BusinessClaims.legalName]
            it[displayName] = claim[BusinessClaims.displayName]
            it[logoKey] = claim[BusinessClaims.logoKey]
            it[category] = claim[BusinessClaims.category]
            it[website] = claim[BusinessClaims.website]
            it[hours] = claim[BusinessClaims.hours]
            it[countryIso] = claim[BusinessClaims.countryIso]
            it[status] = ReviewStatus.APPROVED
            it[verifiedAt] = now
            it[updatedAt] = now
        }
        BusinessNumbers.upsert {
            it[e164] = number
            it[BusinessNumbers.businessId] = businessId
            it[defaultCallReason] = claim[BusinessClaims.callReason]
            it[claimedVia] = claim[BusinessClaims.method]
            it[claimedAt] = now
            it[updatedAt] = now
        }
        BusinessClaims.update({ BusinessClaims.id eq claim[BusinessClaims.id] }) {
            it[status] = ClaimStatus.APPROVED.value
            it[decidedAt] = now
            it[BusinessClaims.reviewer] = reviewer
            it[BusinessClaims.businessId] = businessId
        }
        // The logo is the business's now; it isn't deleted with the claim's documents.
        claim[BusinessClaims.logoKey]?.let { logo ->
            BusinessClaimDocuments.deleteWhere { (claimId eq claim[BusinessClaims.id]) and (objectKey eq logo) }
        }
        // Into the lookup index at once, and rescored at the next run so the verified cap takes it off spam lists.
        ensureIndexed(listOf(number))
        NumberReputation.update({ (NumberReputation.e164 eq number) and (NumberReputation.updatedAt greater RESCORE) }) {
            it[updatedAt] = RESCORE
        }
        return businessId
    }

    private fun JdbcTransaction.files(id: UUID): List<ClaimFile> =
        BusinessClaimDocuments
            .selectAll()
            .where { BusinessClaimDocuments.claimId eq id }
            .orderBy(BusinessClaimDocuments.uploadedAt to SortOrder.ASC)
            .map {
                ClaimFile(
                    it[BusinessClaimDocuments.objectKey],
                    it[BusinessClaimDocuments.contentType],
                    it[BusinessClaimDocuments.sizeBytes],
                )
            }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock)

    /** Object keys and file cleanup. */
    companion object {
        private const val QUEUE_LIMIT = 200
        private val log = LoggerFactory.getLogger(ClaimReview::class.java)

        // A time before any real rescoring, so the scoring job treats the number as due (as RetentionJob does).
        private val RESCORE: OffsetDateTime = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

        /** Object-store prefix of claim [claimId]'s files. */
        fun objectPrefix(claimId: UUID) = "claims/$claimId/"

        /** Deletes [keys]; a failure is logged and the next retention run tries again. */
        suspend fun deleteObjects(
            objects: ObjectStore,
            keys: Collection<String>,
        ) {
            keys.forEach { key ->
                try {
                    objects.delete(key)
                } catch (e: ObjectStoreException) {
                    log.warn("Could not delete claim file {}", key, e)
                }
            }
        }
    }
}

private fun toDetail(
    row: ResultRow,
    files: List<ClaimFile>,
) = ClaimDetail(
    id = row[BusinessClaims.id],
    e164 = row[BusinessClaims.e164],
    countryIso = row[BusinessClaims.countryIso],
    legalName = row[BusinessClaims.legalName],
    displayName = row[BusinessClaims.displayName],
    category = row[BusinessClaims.category],
    website = row[BusinessClaims.website],
    hours = row[BusinessClaims.hours],
    callReason = row[BusinessClaims.callReason],
    method = row[BusinessClaims.method],
    status = ClaimStatus.of(row[BusinessClaims.status]),
    logoKey = row[BusinessClaims.logoKey],
    documents = files,
    createdAt = row[BusinessClaims.createdAt],
    verifiedAt = row[BusinessClaims.verifiedAt],
    submittedAt = row[BusinessClaims.submittedAt],
    decidedAt = row[BusinessClaims.decidedAt],
    reviewer = row[BusinessClaims.reviewer],
    reviewNote = row[BusinessClaims.reviewNote],
    businessId = row[BusinessClaims.businessId],
)
