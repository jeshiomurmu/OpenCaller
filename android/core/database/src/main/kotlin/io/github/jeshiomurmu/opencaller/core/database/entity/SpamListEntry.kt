package io.github.jeshiomurmu.opencaller.core.database.entity

import androidx.room.Entity

/** One number of the downloaded on-device spam list. */
@Entity(tableName = "spam_list", primaryKeys = ["e164"])
data class SpamListEntry(
    val e164: String,
    val score: Float,
    val category: String,
    val reportCount: Int,
    val listVersion: Long,
)
