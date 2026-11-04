package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.jeshiomurmu.opencaller.core.database.entity.FlashMessageEntity
import kotlinx.coroutines.flow.Flow

/** Flash messages kept on the phone. */
@Dao
interface FlashMessageDao {
    /** Stores [message] unless one with its id is already there (a push and a pending fetch can both bring it). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: FlashMessageEntity): Long

    /** The newest [limit] messages, newest first. */
    @Query("SELECT * FROM flash_message ORDER BY atMillis DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<FlashMessageEntity>>

    /** Deletes messages older than [beforeMillis]; returns how many. */
    @Query("DELETE FROM flash_message WHERE atMillis < :beforeMillis")
    suspend fun deleteOlderThan(beforeMillis: Long): Int

    /** Deletes every message. */
    @Query("DELETE FROM flash_message")
    suspend fun deleteAll()
}
