package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.jeshiomurmu.opencaller.core.database.entity.MessageMetaEntity
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import kotlinx.coroutines.flow.Flow

/** SMS metadata for the smart inbox. */
@Dao
interface MessageMetaDao {
    /** Inserts or replaces [meta]. */
    @Upsert
    suspend fun upsert(meta: MessageMetaEntity)

    /** Metadata for Telephony provider message [providerId], or `null`. */
    @Query("SELECT * FROM message_meta WHERE providerId = :providerId")
    suspend fun find(providerId: Long): MessageMetaEntity?

    /** Messages in [category], newest first. */
    @Query("SELECT * FROM message_meta WHERE category = :category ORDER BY at DESC")
    fun observeByCategory(category: MessageCategory): Flow<List<MessageMetaEntity>>

    /** Messages in [category] received at or after [atMillis]. */
    @Query("SELECT * FROM message_meta WHERE category = :category AND at >= :atMillis ORDER BY at")
    suspend fun since(
        category: MessageCategory,
        atMillis: Long,
    ): List<MessageMetaEntity>

    /** Every message's metadata (the smart inbox sorts conversations by it). */
    @Query("SELECT * FROM message_meta")
    fun observeAll(): Flow<List<MessageMetaEntity>>

    /** Messages in [categories] received before [atMillis] (the inbox cleaner's candidates). */
    @Query("SELECT * FROM message_meta WHERE category IN (:categories) AND at < :atMillis")
    suspend fun olderThan(
        categories: List<MessageCategory>,
        atMillis: Long,
    ): List<MessageMetaEntity>

    /** Forgets the one-time codes of messages received before [atMillis]; returns how many. */
    @Query("UPDATE message_meta SET otpCode = NULL WHERE otpCode IS NOT NULL AND at < :atMillis")
    suspend fun clearCodesBefore(atMillis: Long): Int

    /** Removes metadata for a deleted message. */
    @Query("DELETE FROM message_meta WHERE providerId = :providerId")
    suspend fun delete(providerId: Long)

    /** Removes the metadata of every message in thread [threadId] (the conversation was deleted). */
    @Query("DELETE FROM message_meta WHERE threadId = :threadId")
    suspend fun deleteThread(threadId: Long): Int
}
