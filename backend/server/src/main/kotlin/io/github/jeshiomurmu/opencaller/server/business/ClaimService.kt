package io.github.jeshiomurmu.opencaller.server.business

import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpDeliveryException
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpService
import io.github.jeshiomurmu.opencaller.server.auth.otp.invalidCodeFormat
import io.github.jeshiomurmu.opencaller.server.auth.otp.toProblem
import io.github.jeshiomurmu.opencaller.server.db.BusinessClaimDocuments
import io.github.jeshiomurmu.opencaller.server.db.BusinessClaims
import io.github.jeshiomurmu.opencaller.server.db.BusinessNumbers
import io.github.jeshiomurmu.opencaller.server.db.Businesses
import io.github.jeshiomurmu.opencaller.server.db.ReviewStatus
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.enforce
import io.github.jeshiomurmu.opencaller.server.http.invalidNumber
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.profile.activeUser
import io.github.jeshiomurmu.opencaller.server.redis.RateLimit
import io.github.jeshiomurmu.opencaller.server.redis.RateLimiter
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStore
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Codes that prove control of a claimed number: one pending code per number (Redis `otp-claim:<e164>`), sent by
 * SMS or, when [voice] is configured, read aloud in a phone call. Both share the store, so either checks it.
 */
class ClaimCodes(
    val sms: OtpService,
    val voice: OtpService?,
) {
    /** The service sending codes by [method]; 400 if phone calls aren't configured on this server. */
    fun by(method: ClaimMethod): OtpService =
        when (method) {
            ClaimMethod.SMS -> sms
            ClaimMethod.VOICE -> voice ?: throw invalidClaim("Codes by phone call aren't available on this server; use sms.")
        }
}

/**
 * Business claims from the claimant's side (docs/prd.md F-BZ-01): start a claim (a code goes to the number), prove
 * control with the code, upload documents (presigned PUTs into the object store, checked on submit), submit for
 * review, list and withdraw. Nothing can be uploaded or submitted before the number is proven (TC-BIZ-001).
 * Approval and rejection are the admin's ([ClaimReview]).
 */
class ClaimService(
    private val db: ServerDatabase,
    private val codes: ClaimCodes,
    private val objects: ObjectStore,
    private val rateLimiter: RateLimiter,
    private val clock: Clock,
) {
    /** [userId] claims the number of [request]; the code goes out by the chosen method. */
    suspend fun start(
        userId: UUID,
        request: StartClaimRequest,
    ): ClaimResponse {
        val number = E164.parseOrNull(request.e164) ?: throw invalidNumber()
        val country = regionOf(number) ?: throw invalidNumber("Claim a number that belongs to a country (not a global service number).")
        ClaimRules.check(request)
        val method = ClaimMethod.of(request.method)
        val sender = codes.by(method)
        enforce(rateLimiter.hit(START_PER_USER, userId.toString()), TOO_MANY_CLAIMS)
        enforce(rateLimiter.hit(START_PER_NUMBER, number.value), TOO_MANY_CLAIMS)
        val id = UUID.randomUUID()
        db.query {
            activeUser(userId)
            requireValid(!isVerifiedBusiness(number.value)) { alreadyVerified() }
            requireValid(openClaim(number.value) == null) {
                ApiProblem(HttpStatusCode.Conflict, "claim_open", "This number already has a claim in progress")
            }
            insertClaim(id, userId, country, request, method)
        }
        sendCode(sender, number) { db.query { BusinessClaims.deleteWhere { BusinessClaims.id eq id } } }
        return mine(userId, id).copy(codeExpiresInSeconds = sender.validFor.inWholeSeconds.toInt())
    }

    /** Sends a new code for claim [claimId] (by [method]); allowed until the number is proven. */
    suspend fun resend(
        userId: UUID,
        claimId: UUID,
        method: String,
    ): ClaimResponse {
        val sender = codes.by(ClaimMethod.of(method))
        val claim = mine(userId, claimId)
        requireValid(claim.status == ClaimStatus.VERIFYING.value) { wrongStep("The number is already proven.") }
        val number = checkNotNull(E164.parseOrNull(claim.e164))
        enforce(rateLimiter.hit(START_PER_NUMBER, number.value), TOO_MANY_CLAIMS)
        sendCode(sender, number) {}
        db.query { BusinessClaims.update({ BusinessClaims.id eq claimId }) { it[BusinessClaims.method] = method } }
        return mine(userId, claimId).copy(codeExpiresInSeconds = sender.validFor.inWholeSeconds.toInt())
    }

    /** Checks [code] for claim [claimId]; the right code proves control of the number. */
    suspend fun verify(
        userId: UUID,
        claimId: UUID,
        code: String,
    ): ClaimResponse {
        if (!OtpService.isWellFormed(code)) throw invalidCodeFormat()
        val claim = mine(userId, claimId)
        requireValid(claim.status == ClaimStatus.VERIFYING.value) { wrongStep("The number is already proven.") }
        val number = checkNotNull(E164.parseOrNull(claim.e164))
        codes.sms
            .verify(number, code)
            .toProblem()
            ?.let { throw it }
        db.query {
            BusinessClaims.update({ (BusinessClaims.id eq claimId) and (BusinessClaims.status eq ClaimStatus.VERIFYING.value) }) {
                it[status] = ClaimStatus.VERIFIED.value
                it[verifiedAt] = OffsetDateTime.now(clock)
            }
        }
        return mine(userId, claimId)
    }

    /** A presigned URL for one file of claim [claimId]; only once the number is proven. */
    suspend fun upload(
        userId: UUID,
        claimId: UUID,
        request: ClaimUploadRequest,
    ): ClaimUploadResponse {
        val kind = UploadKind.of(request.kind)
        val extension =
            kind.contentTypes[request.contentType]
                ?: throw invalidClaim("contentType for a ${kind.value} must be one of ${kind.contentTypes.keys.joinToString()}.")
        val key = "${ClaimReview.objectPrefix(claimId)}${kind.keyPrefix}${UUID.randomUUID()}.$extension"
        db.query {
            requireVerified(userId, claimId)
            requireValid(issuedUploads(claimId).size < ClaimRules.MAX_UPLOADS) {
                invalidClaim("A claim takes at most ${ClaimRules.MAX_UPLOADS} uploads.")
            }
            // Recorded now with size 0 so withdrawal and retention find the object even if it is never submitted.
            BusinessClaimDocuments.insert {
                it[BusinessClaimDocuments.claimId] = claimId
                it[objectKey] = key
                it[contentType] = request.contentType
                it[sizeBytes] = 0
            }
        }
        val url = objects.presignPut(key, request.contentType, UPLOAD_URL_SECONDS).toString()
        return ClaimUploadResponse(url, key, request.contentType, kind.maxBytes, UPLOAD_URL_SECONDS)
    }

    /** Submits claim [claimId] for review with its uploaded documents (and logo); each file is checked first. */
    suspend fun submit(
        userId: UUID,
        claimId: UUID,
        request: SubmitClaimRequest,
    ): ClaimResponse {
        val documents = request.documentKeys.distinct()
        requireValid(documents.size in 1..ClaimRules.MAX_DOCUMENTS) { invalidClaim("Send 1 to ${ClaimRules.MAX_DOCUMENTS} documentKeys.") }
        val issued = db.query { requireVerified(userId, claimId).let { issuedUploads(claimId) } }
        val sizes = documents.associateWith { checkUpload(issued, it, UploadKind.DOCUMENT) }
        val logoSize = request.logoKey?.let { checkUpload(issued, it, UploadKind.LOGO) }
        db.query {
            requireVerified(userId, claimId)
            (sizes + listOfNotNull(request.logoKey?.let { it to checkNotNull(logoSize) })).forEach { (key, size) ->
                BusinessClaimDocuments.update(
                    { (BusinessClaimDocuments.claimId eq claimId) and (BusinessClaimDocuments.objectKey eq key) },
                ) {
                    it[sizeBytes] = size
                }
            }
            BusinessClaims.update({ BusinessClaims.id eq claimId }) {
                it[status] = ClaimStatus.SUBMITTED.value
                it[submittedAt] = OffsetDateTime.now(clock)
                it[logoKey] = request.logoKey
            }
        }
        return mine(userId, claimId)
    }

    /** [userId]'s claims, newest first. */
    suspend fun list(userId: UUID): ClaimsResponse =
        db.query {
            activeUser(userId)
            ClaimsResponse(
                BusinessClaims
                    .selectAll()
                    .where { BusinessClaims.userId eq userId }
                    .orderBy(BusinessClaims.createdAt to SortOrder.DESC)
                    .limit(LIST_LIMIT)
                    .map(::toResponse),
            )
        }

    /** Withdraws an undecided claim; its files are deleted at once. */
    suspend fun withdraw(
        userId: UUID,
        claimId: UUID,
    ) {
        val claim = mine(userId, claimId)
        requireValid(ClaimStatus.of(claim.status).isOpen) { wrongStep("Only a claim in progress can be withdrawn.") }
        val keys =
            db.query {
                BusinessClaims.update({ BusinessClaims.id eq claimId }) {
                    it[status] = ClaimStatus.WITHDRAWN.value
                    it[decidedAt] = OffsetDateTime.now(clock)
                }
                issuedUploads(claimId).keys.also { BusinessClaimDocuments.deleteWhere { BusinessClaimDocuments.claimId eq claimId } }
            }
        ClaimReview.deleteObjects(objects, keys)
    }

    private suspend fun sendCode(
        sender: OtpService,
        number: E164,
        undo: suspend () -> Unit,
    ) {
        try {
            sender.start(number)
        } catch (e: OtpDeliveryException) {
            log.warn("Claim code for {} could not be delivered", number, e)
            undo()
            throw ApiProblem(
                HttpStatusCode.ServiceUnavailable,
                "otp_delivery_failed",
                "The code could not be sent",
                "Try again in a few minutes.",
            )
        }
    }

    private suspend fun mine(
        userId: UUID,
        claimId: UUID,
    ): ClaimResponse =
        db.query {
            activeUser(userId)
            BusinessClaims
                .selectAll()
                .where { (BusinessClaims.id eq claimId) and (BusinessClaims.userId eq userId) }
                .singleOrNull()
                ?.let(::toResponse)
                ?: throw claimNotFound()
        }

    private suspend fun checkUpload(
        issued: Map<String, String>,
        key: String,
        kind: UploadKind,
    ): Long {
        val issuedType = issued[key]
        requireValid(issuedType != null && key.substringAfterLast('/').startsWith(kind.keyPrefix)) {
            invalidClaim("$key isn't a ${kind.value} upload of this claim.")
        }
        val info = objects.head(key) ?: throw invalidClaim("Upload $key before submitting.")
        requireValid(info.size in 1..kind.maxBytes && info.contentType == issuedType) {
            invalidClaim("$key must be a ${kind.contentTypes.keys.joinToString(" or ")} of at most ${kind.maxBytes} bytes.")
        }
        return info.size
    }

    private companion object {
        val log = LoggerFactory.getLogger(ClaimService::class.java)
        val START_PER_USER = RateLimit("claim_start_user", maxHits = 5, window = 1.days)
        val START_PER_NUMBER = RateLimit("claim_start_number", maxHits = 3, window = 1.hours)
        const val TOO_MANY_CLAIMS = "Too many claim codes"
        const val UPLOAD_URL_SECONDS = 600
        const val LIST_LIMIT = 50
    }
}

/** The claimant's view of a claim row. */
internal fun toResponse(row: ResultRow): ClaimResponse =
    ClaimResponse(
        id = row[BusinessClaims.id].toString(),
        e164 = row[BusinessClaims.e164],
        displayName = row[BusinessClaims.displayName],
        category = row[BusinessClaims.category],
        method = row[BusinessClaims.method],
        status = row[BusinessClaims.status],
        reviewNote = row[BusinessClaims.reviewNote].takeIf { row[BusinessClaims.status] == ClaimStatus.REJECTED.value },
        businessId = row[BusinessClaims.businessId]?.toString(),
        createdAt = row[BusinessClaims.createdAt].toInstant().toEpochMilli(),
    )

/** `true` if [e164] already belongs to an approved business. */
internal fun JdbcTransaction.isVerifiedBusiness(e164: String): Boolean =
    BusinessNumbers
        .join(Businesses, JoinType.INNER, BusinessNumbers.businessId, Businesses.id)
        .select(BusinessNumbers.e164)
        .where { (BusinessNumbers.e164 eq e164) and (Businesses.status eq ReviewStatus.APPROVED) }
        .any()

/** The open claim of [e164], if any. */
internal fun JdbcTransaction.openClaim(e164: String): UUID? =
    BusinessClaims
        .select(BusinessClaims.id)
        .where {
            (BusinessClaims.e164 eq e164) and (BusinessClaims.status inList ClaimStatus.entries.filter { it.isOpen }.map { it.value })
        }.singleOrNull()
        ?.get(BusinessClaims.id)

/** 409 `already_verified`. */
internal fun alreadyVerified() =
    ApiProblem(HttpStatusCode.Conflict, "already_verified", "This number already belongs to a verified business")

/** The number's country (libphonenumber region, e.g. `BD`), or `null` for non-geographic numbers (`001`). */
internal fun regionOf(number: E164): String? {
    val util = PhoneNumberUtil.getInstance()
    return util.getRegionCodeForNumber(util.parse(number.value, null))?.takeIf { it.length == 2 }
}

/** [userId]'s claim [claimId] must be at `verified` (the number proven, nothing submitted yet). */
internal fun JdbcTransaction.requireVerified(
    userId: UUID,
    claimId: UUID,
) {
    activeUser(userId)
    val status =
        BusinessClaims
            .select(BusinessClaims.status)
            .where { (BusinessClaims.id eq claimId) and (BusinessClaims.userId eq userId) }
            .singleOrNull()
            ?.get(BusinessClaims.status)
            ?: throw claimNotFound()
    // TC-BIZ-001: no upload or submission without proof of the number.
    requireValid(status == ClaimStatus.VERIFIED.value) {
        if (status == ClaimStatus.VERIFYING.value) {
            ApiProblem(
                HttpStatusCode.Forbidden,
                "number_not_verified",
                "Prove control of the number first",
                "Enter the code sent to the number.",
            )
        } else {
            wrongStep("This claim can no longer be changed.")
        }
    }
}

/** Object keys of the uploads issued for [claimId], with the content type each was signed for. */
internal fun JdbcTransaction.issuedUploads(claimId: UUID): Map<String, String> =
    BusinessClaimDocuments
        .select(BusinessClaimDocuments.objectKey, BusinessClaimDocuments.contentType)
        .where { BusinessClaimDocuments.claimId eq claimId }
        .associate { it[BusinessClaimDocuments.objectKey] to it[BusinessClaimDocuments.contentType] }

private fun JdbcTransaction.insertClaim(
    id: UUID,
    userId: UUID,
    country: String,
    request: StartClaimRequest,
    method: ClaimMethod,
) {
    BusinessClaims.insert {
        it[BusinessClaims.id] = id
        it[BusinessClaims.userId] = userId
        // Equal to the number's E.164 form: E164.parseOrNull only accepts canonical input.
        it[e164] = request.e164
        it[countryIso] = country
        it[legalName] = request.legalName
        it[displayName] = request.displayName
        it[category] = request.category
        it[website] = request.website
        it[hours] = request.hours
        it[callReason] = request.callReason
        it[BusinessClaims.method] = method.value
        it[status] = ClaimStatus.VERIFYING.value
    }
}
