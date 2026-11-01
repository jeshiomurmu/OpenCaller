package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.jeshiomurmu.opencaller.core.database.entity.ScheduledSmsEntity
import io.github.jeshiomurmu.opencaller.core.model.SyncState
import kotlinx.coroutines.flow.Flow

/** Scheduled SMS. */
@Dao
interface ScheduledSmsDao {
    /** Schedules a message and returns its id. */
    @Insert
    suspend fun insert(sms: ScheduledSmsEntity): Long

    /** Messages in [state], soonest first. */
    @Query("SELECT * FROM scheduled_sms WHERE state = :state ORDER BY sendAt ASC")
    fun observeInState(state: SyncState): Flow<List<ScheduledSmsEntity>>

    /** Message [id], or `null`. */
    @Query("SELECT * FROM scheduled_sms WHERE id = :id")
    suspend fun find(id: Long): ScheduledSmsEntity?

    /** Sets the WorkManager work name of message [id]. */
    @Query("UPDATE scheduled_sms SET workId = :workId WHERE id = :id")
    suspend fun updateWork(
        id: Long,
        workId: String,
    )

    /** Removes message [id] (cancelled). */
    @Query("DELETE FROM scheduled_sms WHERE id = :id")
    suspend fun delete(id: Long)

    /** Updates the state of message [id]. */
    @Query("UPDATE scheduled_sms SET state = :state WHERE id = :id")
    suspend fun updateState(
        id: Long,
        state: SyncState,
    )
}
