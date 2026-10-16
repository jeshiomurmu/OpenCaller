package io.github.jeshiomurmu.opencaller.server.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// Exposed mirrors of the tables in db/migration/V1__init.sql (docs/architecture.md §7). Flyway owns the schema;
// these objects are only used for queries. ServerSchemaTest compares them with the migrated database.

private const val COUNTRY_ISO_LENGTH = 2

/** `users`. */
object Users : Table("users") {
    val id = javaUUID("id")
    val e164 = text("e164").uniqueIndex()
    val verifiedAt = timestampWithTimeZone("verified_at")
    val displayName = text("display_name").nullable()
    val avatarKey = text("avatar_key").nullable()
    val videoCallerIdKey = text("video_caller_id_key").nullable()
    val visibility = pgEnum<Visibility>("visibility", "visibility").default(Visibility.REGISTERED_ONLY)
    val incognito = bool("incognito").default(false)
    val supporterTier = text("supporter_tier").default("none")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val deletedAt = timestampWithTimeZone("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** `devices`. */
object Devices : Table("devices") {
    val id = javaUUID("id")
    val userId = optReference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val flavor = text("flavor")
    val pushType = text("push_type").nullable()
    val pushToken = text("push_token").nullable()
    val integrityLevel = short("integrity_level").default(0)
    val lastSeen = timestampWithTimeZone("last_seen").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** `refresh_tokens`: SHA-256 of each refresh token; rotated tokens are kept to detect reuse. */
object RefreshTokens : Table("refresh_tokens") {
    val tokenHash = text("token_hash")
    val deviceId = optReference("device_id", Devices.id, onDelete = ReferenceOption.CASCADE)
    val expiresAt = timestampWithTimeZone("expires_at")
    val rotatedAt = timestampWithTimeZone("rotated_at").nullable()
    override val primaryKey = PrimaryKey(tokenHash)
}

/** `businesses`. */
object Businesses : Table("businesses") {
    val id = javaUUID("id")
    val legalName = text("legal_name")
    val displayName = text("display_name")
    val logoKey = text("logo_key").nullable()
    val category = text("category").nullable()
    val website = text("website").nullable()
    val countryIso = char("country_iso", COUNTRY_ISO_LENGTH)
    val status = pgEnum<ReviewStatus>("status", "review_status").default(ReviewStatus.PENDING)
    val verifiedAt = timestampWithTimeZone("verified_at").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(id)
}

/** `business_numbers`. */
object BusinessNumbers : Table("business_numbers") {
    val e164 = text("e164")
    val businessId = optReference("business_id", Businesses.id, onDelete = ReferenceOption.CASCADE)
    val defaultCallReason = text("default_call_reason").nullable()
    val claimedVia = text("claimed_via")
    val claimedAt = timestampWithTimeZone("claimed_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(e164)
}

/** `sender_ids`. */
object SenderIds : Table("sender_ids") {
    val countryIso = char("country_iso", COUNTRY_ISO_LENGTH)
    val header = text("header")
    val brandName = text("brand_name")
    val category = text("category")
    val sourceName = text("source")
    val businessId = optReference("business_id", Businesses.id)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(countryIso, header)
}

/** `number_reputation`. */
object NumberReputation : Table("number_reputation") {
    val e164 = text("e164")
    val countryCode = short("country_code")
    val bucket16 = integer("bucket_16")
    val spamScore = float("spam_score").default(0f)
    val category = text("category").nullable()
    val reportCount = integer("report_count").default(0)
    val notSpamCount = integer("not_spam_count").default(0)
    val lineType = text("line_type").nullable()
    val firstSeen = timestampWithTimeZone("first_seen").defaultExpression(CurrentTimestampWithTimeZone)
    val lastReportAt = timestampWithTimeZone("last_report_at").nullable()
    val modelVersion = text("model_version").nullable()
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(e164)
}

/** `reports`. */
object Reports : Table("reports") {
    val id = long("id").autoIncrement()
    val e164 = text("e164")
    val reporterId = optReference("reporter_id", Users.id, onDelete = ReferenceOption.SET_NULL)
    val label = pgEnum<ReportLabel>("label", "report_label")
    val category = text("category").nullable()
    val comment = text("comment").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(id)
}

/** `reporter_trust`. */
object ReporterTrust : Table("reporter_trust") {
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val trust = float("trust").default(DEFAULT_REPORTER_TRUST)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(userId)
}

/** Default `reporter_trust.trust` (the column default in V1). */
const val DEFAULT_REPORTER_TRUST = 0.5f

/** `honeypot_hits`. */
object HoneypotHits : Table("honeypot_hits") {
    val id = long("id").autoIncrement()
    val e164 = text("e164")
    val honeypot = text("honeypot")
    val at = timestampWithTimeZone("at")
    override val primaryKey = PrimaryKey(id)
}

/** `disputes`. */
object Disputes : Table("disputes") {
    val id = long("id").autoIncrement()
    val e164 = text("e164")
    val claimantUserId = optReference("claimant_user_id", Users.id)
    val reason = text("reason")
    val status = pgEnum<ReviewStatus>("status", "review_status").default(ReviewStatus.PENDING)
    val reviewer = text("reviewer").nullable()
    val resolvedAt = timestampWithTimeZone("resolved_at").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(id)
}

/** `unlist_requests`. */
object UnlistRequests : Table("unlist_requests") {
    val id = long("id").autoIncrement()
    val e164 = text("e164")
    val verifiedAt = timestampWithTimeZone("verified_at").nullable()
    val processedAt = timestampWithTimeZone("processed_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

/** `profile_views` (`kind` is `view` or `search`). */
object ProfileViews : Table("profile_views") {
    val id = long("id").autoIncrement()
    val viewedUserId = optReference("viewed_user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val viewerUserId = optReference("viewer_user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val kind = text("kind")
    val at = timestampWithTimeZone("at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(id)
}

/** `contact_requests`. */
object ContactRequests : Table("contact_requests") {
    val id = long("id").autoIncrement()
    val requesterId = optReference("requester_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val targetId = optReference("target_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val status = pgEnum<ReviewStatus>("status", "review_status").default(ReviewStatus.PENDING)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(id)
}

/** `subscriptions`. */
object Subscriptions : Table("subscriptions") {
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val platform = text("platform")
    val productId = text("product_id")
    val purchaseTokenHash = text("purchase_token_hash")
    val status = text("status")
    val expiresAt = timestampWithTimeZone("expires_at").nullable()
    override val primaryKey = PrimaryKey(userId, platform)
}

/** `family_groups`. */
object FamilyGroups : Table("family_groups") {
    val id = javaUUID("id")
    val ownerId = optReference("owner_id", Users.id, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(id)
}

/** `family_members`. */
object FamilyMembers : Table("family_members") {
    val groupId = reference("group_id", FamilyGroups.id, onDelete = ReferenceOption.CASCADE)
    val userId = reference("user_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val role = text("role")
    val consentedAt = timestampWithTimeZone("consented_at")
    override val primaryKey = PrimaryKey(groupId, userId)
}

/** `list_versions` (`kind` is `full`, `delta` or `extended`). */
object ListVersions : Table("list_versions") {
    val countryIso = char("country_iso", COUNTRY_ISO_LENGTH)
    val version = long("version")
    val kind = text("kind")
    val objectKey = text("object_key")
    val sha256 = text("sha256")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(countryIso, version, kind)
}

/** Every table in V1, in creation order. */
val allTables: List<Table> =
    listOf(
        Users,
        Devices,
        RefreshTokens,
        Businesses,
        BusinessNumbers,
        SenderIds,
        NumberReputation,
        Reports,
        ReporterTrust,
        HoneypotHits,
        Disputes,
        UnlistRequests,
        ProfileViews,
        ContactRequests,
        Subscriptions,
        FamilyGroups,
        FamilyMembers,
        ListVersions,
    )
