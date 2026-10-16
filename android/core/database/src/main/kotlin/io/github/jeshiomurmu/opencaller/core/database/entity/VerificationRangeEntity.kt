package io.github.jeshiomurmu.opencaller.core.database.entity

import androidx.room.Entity

/** An E.164 prefix used by a flash-call verification provider. */
@Entity(tableName = "verification_range", primaryKeys = ["prefix"])
data class VerificationRangeEntity(
    val prefix: String,
    val provider: String,
)
