package io.github.jeshiomurmu.opencaller.core.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * A call the AI assistant answered (schema version 6, ADR 0010), kept on this phone only. [e164] is the caller
 * (`null` when hidden); [urgency] and [endReason] are model enum names; [transcript] holds one line per row of the
 * conversation, each prefixed with `C ` (caller) or `A ` (assistant); times are epoch millis.
 */
@Entity(tableName = "screened_call", primaryKeys = ["id"], indices = [Index("startedAt")])
data class ScreenedCallEntity(
    val id: String,
    val startedAt: Long,
    val e164: String?,
    val name: String?,
    val reason: String?,
    val urgency: String,
    val likelySpam: Boolean,
    val transcript: String,
    val endReason: String,
)
