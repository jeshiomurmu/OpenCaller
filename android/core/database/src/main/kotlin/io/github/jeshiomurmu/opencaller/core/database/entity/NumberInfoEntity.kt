package io.github.jeshiomurmu.opencaller.core.database.entity

import androidx.room.Entity
import io.github.jeshiomurmu.opencaller.core.model.NameSource

/**
 * Cached caller information for a number (reputation cache, TTL via [expiresAt]). Times are epoch millis.
 * [badge] (`SUPPORTER` or `GOLD`, schema version 2) is the owner's supporter badge, set only with a name.
 * [videoId] (schema version 5) is the owner's video caller ID, as visible as the name.
 */
@Entity(tableName = "number_info", primaryKeys = ["e164"])
data class NumberInfoEntity(
    val e164: String,
    val displayName: String?,
    val nameSource: NameSource?,
    val spamScore: Float?,
    val spamCategory: String?,
    val reportCount: Int,
    val isBusiness: Boolean,
    val businessId: String?,
    val verified: Boolean,
    val logoUrl: String?,
    val callReason: String?,
    val lineType: String?,
    val fetchedAt: Long,
    val expiresAt: Long,
    val badge: String? = null,
    val videoId: String? = null,
)
