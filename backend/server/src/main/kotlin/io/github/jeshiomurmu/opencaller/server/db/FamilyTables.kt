package io.github.jeshiomurmu.opencaller.server.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.CurrentTimestampWithTimeZone
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone

// Exposed mirrors of the Phase 12 tables (V7__family_scam_feed_portal.sql; ADR 0008).

/** `family_invites`: single-use codes, kept as their SHA-256 only. */
object FamilyInvites : Table("family_invites") {
    val codeHash = text("code_hash")
    val groupId = reference("group_id", FamilyGroups.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val expiresAt = timestampWithTimeZone("expires_at")
    val usedAt = timestampWithTimeZone("used_at").nullable()
    override val primaryKey = PrimaryKey(codeHash)
}

/** `family_alerts`: a member's alert for the admin; [kind] is `otp_during_call` or `otp_burst`. Kept 30 days. */
object FamilyAlerts : Table("family_alerts") {
    val id = javaUUID("id")
    val groupId = reference("group_id", FamilyGroups.id, onDelete = ReferenceOption.CASCADE)
    val memberId = reference("member_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val kind = text("kind")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(id)
}

/** `family_daily_counts`: a member's counts for one day (calls blocked, messages filtered). Kept 8 days. */
object FamilyDailyCounts : Table("family_daily_counts") {
    val memberId = reference("member_id", Users.id, onDelete = ReferenceOption.CASCADE)
    val day = date("day")
    val blockedCalls = integer("blocked_calls")
    val spamMessages = integer("spam_messages")
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(memberId, day)
}

/** `scam_cards`: the scam feed, curated in the admin web per country and language. */
object ScamCards : Table("scam_cards") {
    val id = javaUUID("id")
    val countryIso = char("country_iso", COUNTRY_ISO_LENGTH)
    val language = text("language")
    val title = text("title")
    val body = text("body")
    val link = text("link").nullable()
    val published = bool("published").default(false)
    val expiresAt = timestampWithTimeZone("expires_at").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestampWithTimeZone)
    override val primaryKey = PrimaryKey(id)
}

/** `business_search_counts`: explicit searches of a verified-business number per day, never who searched. */
object BusinessSearchCounts : Table("business_search_counts") {
    val e164 = text("e164")
    val day = date("day")
    val searches = integer("searches").default(0)
    override val primaryKey = PrimaryKey(e164, day)
}

/** `portal_sessions`: business dashboard sessions, kept as the SHA-256 of the cookie's token. */
object PortalSessions : Table("portal_sessions") {
    val tokenHash = text("token_hash")
    val businessId = reference("business_id", Businesses.id, onDelete = ReferenceOption.CASCADE)
    val e164 = text("e164")
    val csrf = text("csrf")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestampWithTimeZone)
    val expiresAt = timestampWithTimeZone("expires_at")
    override val primaryKey = PrimaryKey(tokenHash)
}
