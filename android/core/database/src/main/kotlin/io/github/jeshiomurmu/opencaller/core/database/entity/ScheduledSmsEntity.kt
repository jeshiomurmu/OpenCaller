package io.github.jeshiomurmu.opencaller.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.jeshiomurmu.opencaller.core.model.SyncState

/**
 * An SMS scheduled to be sent later by WorkManager.
 *
 * @property to recipient (stored in column `to_address`, since `TO` is an SQL keyword).
 * @property subId SIM subscription id to send from.
 */
@Entity(tableName = "scheduled_sms")
data class ScheduledSmsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "to_address") val to: String,
    val body: String,
    val subId: Int,
    val sendAt: Long,
    val workId: String,
    val state: SyncState,
)
