package io.github.jeshiomurmu.opencaller.core.database.entity

import androidx.room.Entity

/**
 * A Flash message on this phone (schema version 5): received ones are deleted from the server once fetched, sent
 * ones were never stored there after delivery. [kind] is the server's name (`call_me`, …); [e164] is the other
 * party; times are epoch millis.
 */
@Entity(tableName = "flash_message", primaryKeys = ["id"])
data class FlashMessageEntity(
    val id: String,
    val kind: String,
    val e164: String,
    val name: String?,
    val atMillis: Long,
    val outgoing: Boolean,
)
