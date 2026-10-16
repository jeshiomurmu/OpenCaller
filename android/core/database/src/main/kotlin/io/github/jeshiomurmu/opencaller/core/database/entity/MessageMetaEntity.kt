package io.github.jeshiomurmu.opencaller.core.database.entity

import androidx.room.Entity
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import io.github.jeshiomurmu.opencaller.core.model.NameSource

/**
 * Metadata about an SMS stored in the Telephony provider (the body itself stays there, never here).
 *
 * @property providerId the Telephony provider `_id`.
 * @property riskFlags bitmask of risk signals (phishing link, OTP during call, unrequested OTP, …).
 */
@Entity(tableName = "message_meta", primaryKeys = ["providerId"])
data class MessageMetaEntity(
    val providerId: Long,
    val threadId: Long,
    val category: MessageCategory,
    val senderName: String?,
    val senderSource: NameSource?,
    val otpCode: String?,
    val otpOrigin: String?,
    val otpExpiresAt: Long?,
    val riskFlags: Int,
    val classifierScore: Float,
    val at: Long,
)
