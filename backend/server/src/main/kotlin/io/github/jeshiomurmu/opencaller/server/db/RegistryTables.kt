package io.github.jeshiomurmu.opencaller.server.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// Exposed mirrors of the tables V4__business_and_registries.sql adds (Phase 9).

/**
 * `business_claims`: [method] `sms` | `voice`; [status] `verifying` → `verified` → `submitted` → `approved` |
 * `rejected` (or `withdrawn`).
 */
object BusinessClaims : Table("business_claims") {
    val id = javaUUID("id")
    val userId = optReference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val e164 = text("e164")
    val countryIso = char("country_iso", COUNTRY_ISO_LENGTH)
    val legalName = text("legal_name")
    val displayName = text("display_name")
    val category = text("category")
    val website = text("website").nullable()
    val hours = text("hours").nullable()
    val callReason = text("call_reason").nullable()
    val logoKey = text("logo_key").nullable()
    val method = text("method")
    val status = text("status")
    val verifiedAt = timestampWithTimeZone("verified_at").nullable()
    val submittedAt = timestampWithTimeZone("submitted_at").nullable()
    val decidedAt = timestampWithTimeZone("decided_at").nullable()
    val reviewer = text("reviewer").nullable()
    val reviewNote = text("review_note").nullable()
    val businessId = optReference("business_id", Businesses.id, onDelete = ReferenceOption.SET_NULL)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(id)
}

/** `business_claim_documents`. */
object BusinessClaimDocuments : Table("business_claim_documents") {
    val claimId = reference("claim_id", BusinessClaims.id, onDelete = ReferenceOption.CASCADE)
    val objectKey = text("object_key")
    val contentType = text("content_type")
    val sizeBytes = long("size_bytes")
    val uploadedAt = timestampWithTimeZone("uploaded_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(claimId, objectKey)
}

/** `admin_audit`: one row per admin action. */
object AdminAudit : Table("admin_audit") {
    val id = long("id").autoIncrement()
    val at = timestampWithTimeZone("at").defaultExpression(CurrentTimestampWithTimeZone)
    val admin = text("admin")
    val ip = text("ip")
    val action = text("action")
    val target = text("target")
    val detail = text("detail").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** `otp_app_hashes`: SMS Retriever app hashes; a removed row keeps [deletedAt]. */
object OtpAppHashes : Table("otp_app_hashes") {
    val hash = text("hash")
    val packageName = text("package_name")
    val appName = text("app_name")
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(hash)
}

/** `verification_ranges`: number prefixes of flash-call verification providers; a removed row keeps [deletedAt]. */
object VerificationRanges : Table("verification_ranges") {
    val prefix = text("prefix")
    val provider = text("provider")
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(prefix)
}

/** `import_runs`: [status] `ok` | `failed` | `unavailable`. */
object ImportRuns : Table("import_runs") {
    val id = long("id").autoIncrement()
    val sourceName = text("source")
    val countryIso = char("country_iso", COUNTRY_ISO_LENGTH).nullable()
    val startedAt = timestampWithTimeZone("started_at")
    val finishedAt = timestampWithTimeZone("finished_at")
    val status = text("status")
    val upserted = integer("upserted")
    val removed = integer("removed")
    val detail = text("detail").nullable()
    override val primaryKey = PrimaryKey(id)
}
