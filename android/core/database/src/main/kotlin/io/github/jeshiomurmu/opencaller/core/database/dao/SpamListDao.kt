package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.jeshiomurmu.opencaller.core.database.entity.SpamListEntry

/** On-device spam list. */
@Dao
interface SpamListDao {
    /** Inserts or replaces [entries]. */
    @Upsert
    suspend fun upsertAll(entries: List<SpamListEntry>)

    /** The entry for [e164], or `null` if the number is not listed. */
    @Query("SELECT * FROM spam_list WHERE e164 = :e164")
    suspend fun find(e164: String): SpamListEntry?

    /** Removes entries from list versions older than [version]; returns the number removed. */
    @Query("DELETE FROM spam_list WHERE listVersion < :version")
    suspend fun deleteOlderThan(version: Long): Int

    /** Number of listed numbers. */
    @Query("SELECT COUNT(*) FROM spam_list")
    suspend fun count(): Int
}
