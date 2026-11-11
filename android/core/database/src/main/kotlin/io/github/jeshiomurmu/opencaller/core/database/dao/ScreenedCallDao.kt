package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.jeshiomurmu.opencaller.core.database.entity.ScreenedCallEntity
import kotlinx.coroutines.flow.Flow

/** Calls the assistant answered. */
@Dao
interface ScreenedCallDao {
    /** Inserts or replaces [call] (the live screen saves it again as the call goes on). */
    @Upsert
    suspend fun upsert(call: ScreenedCallEntity)

    /** The newest [limit] calls, newest first. */
    @Query("SELECT * FROM screened_call ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ScreenedCallEntity>>

    /** Call [id], or `null`. */
    @Query("SELECT * FROM screened_call WHERE id = :id")
    suspend fun find(id: String): ScreenedCallEntity?

    /** Deletes call [id]. */
    @Query("DELETE FROM screened_call WHERE id = :id")
    suspend fun delete(id: String)

    /** Deletes calls older than [beforeMillis]; returns how many. */
    @Query("DELETE FROM screened_call WHERE startedAt < :beforeMillis")
    suspend fun deleteOlderThan(beforeMillis: Long): Int
}
