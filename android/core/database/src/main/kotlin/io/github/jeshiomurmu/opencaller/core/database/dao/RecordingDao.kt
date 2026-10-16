package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.jeshiomurmu.opencaller.core.database.entity.RecordingEntity
import kotlinx.coroutines.flow.Flow

/** Call recordings. */
@Dao
interface RecordingDao {
    /** Inserts or replaces [recording]. */
    @Upsert
    suspend fun upsert(recording: RecordingEntity)

    /** All recordings, newest first. */
    @Query("SELECT * FROM recording ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    /** Deletes recording [id]. */
    @Query("DELETE FROM recording WHERE id = :id")
    suspend fun delete(id: String)
}
