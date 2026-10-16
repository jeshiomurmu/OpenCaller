package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.jeshiomurmu.opencaller.core.database.entity.ScreeningEventEntity
import kotlinx.coroutines.flow.Flow

/** Screening decisions (blocked log, debug screen). */
@Dao
interface ScreeningEventDao {
    /** Records an event and returns its id. */
    @Insert
    suspend fun insert(event: ScreeningEventEntity): Long

    /** The [limit] most recent events, newest first. */
    @Query("SELECT * FROM screening_event ORDER BY at DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ScreeningEventEntity>>

    /** Removes events older than [millis]; returns the number removed. */
    @Query("DELETE FROM screening_event WHERE at < :millis")
    suspend fun deleteOlderThan(millis: Long): Int
}
