package io.github.jeshiomurmu.opencaller.core.database.entity

import androidx.room.Entity

/** A row of an official sender-ID registry (SMS header → brand). */
@Entity(tableName = "sender_id", primaryKeys = ["countryIso", "header"])
data class SenderIdEntity(
    val countryIso: String,
    val header: String,
    val brandName: String,
    val category: String,
    val source: String,
    val businessId: String?,
    val updatedAt: Long,
)
