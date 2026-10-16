package io.github.jeshiomurmu.opencaller.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A call recording downloaded from the recording line. */
@Entity(tableName = "recording")
data class RecordingEntity(
    @PrimaryKey val id: String,
    val e164: String?,
    val filePath: String,
    val durationMs: Long,
    val transcript: String?,
    val summary: String?,
    val createdAt: Long,
)
