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

    /** The [limit] most recent events, newest first (one-shot read for exports and statistics). */
    @Query("SELECT * FROM screening_event ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ScreeningEventEntity>

    /** The [limit] most recent blocked or silenced calls, newest first (blocked-calls log). */
    @Query("SELECT * FROM screening_event WHERE decision IN ('BLOCK', 'SILENCE') ORDER BY at DESC, id DESC LIMIT :limit")
    fun observeBlocked(limit: Int): Flow<List<ScreeningEventEntity>>

    /** The event with [id], or `null`. */
    @Query("SELECT * FROM screening_event WHERE id = :id")
    suspend fun find(id: Long): ScreeningEventEntity?

    /** Deletes every event; returns the number removed. */
    @Query("DELETE FROM screening_event")
    suspend fun deleteAll(): Int

    /** Removes events older than [millis]; returns the number removed. */
    @Query("DELETE FROM screening_event WHERE at < :millis")
    suspend fun deleteOlderThan(millis: Long): Int
}
