package io.github.jeshiomurmu.opencaller.core.database.entity

import androidx.room.Entity

/**
 * One number of the extended spam list (docs/prd.md F-PR-03): the listed numbers beyond the standard list's size
 * cap, downloaded only while the user is a supporter. Same columns as [SpamListEntry].
 */
@Entity(tableName = "spam_list_extended", primaryKeys = ["e164"])
data class ExtendedSpamListEntry(
    val e164: String,
    val score: Float,
    val category: String,
    val reportCount: Int,
    val listVersion: Long,
)
