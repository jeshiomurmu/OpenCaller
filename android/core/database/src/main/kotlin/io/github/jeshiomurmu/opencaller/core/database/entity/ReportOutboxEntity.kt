package io.github.jeshiomurmu.opencaller.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.jeshiomurmu.opencaller.core.model.ReportLabel
import io.github.jeshiomurmu.opencaller.core.model.SyncState

/** A spam/not-spam report waiting to be uploaded. */
@Entity(tableName = "report_outbox")
data class ReportOutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val e164: String,
    val label: ReportLabel,
    val category: String?,
    val comment: String?,
    val createdAt: Long,
    val attempts: Int,
    val state: SyncState,
)
