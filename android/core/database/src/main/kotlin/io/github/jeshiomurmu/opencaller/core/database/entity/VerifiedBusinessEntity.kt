package io.github.jeshiomurmu.opencaller.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A verified business number from the server's directory of the phone's country (docs/prd.md F-BZ-02), kept on
 * the phone so a verified business is known before any lookup and offline. [logoFile] is the logo's file name in
 * the app's private storage once downloaded. [updatedAt] is the server's change time (epoch ms).
 */
@Entity(tableName = "verified_business")
data class VerifiedBusinessEntity(
    @PrimaryKey val e164: String,
    val businessId: String,
    val name: String,
    val category: String?,
    val callReason: String?,
    val logoPath: String?,
    val logoFile: String?,
    val updatedAt: Long,
)
