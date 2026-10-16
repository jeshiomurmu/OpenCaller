package io.github.jeshiomurmu.opencaller.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction

/** One call-screening decision, kept for the blocked log and the debug screen. */
@Entity(tableName = "screening_event")
data class ScreeningEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val e164: String?,
    val direction: Direction,
    val decision: Decision,
    val reason: String,
    val spamScore: Float?,
    val stirStatus: Int?,
    val latencyMs: Int,
    val at: Long,
)
