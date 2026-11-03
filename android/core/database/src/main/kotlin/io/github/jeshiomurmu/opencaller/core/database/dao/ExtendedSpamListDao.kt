package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.jeshiomurmu.opencaller.core.database.entity.ExtendedSpamListEntry

/** The extended spam list, always replaced whole (the server publishes it complete for each version). */
@Dao
abstract class ExtendedSpamListDao {
    /** Inserts or replaces [entries]. */
    @Upsert
    abstract suspend fun upsertAll(entries: List<ExtendedSpamListEntry>)

    /** The entry for [e164], or `null` if the number is not on the extended list. */
    @Query("SELECT * FROM spam_list_extended WHERE e164 = :e164")
    abstract suspend fun find(e164: String): ExtendedSpamListEntry?

    /** Deletes every entry; returns the number removed. */
    @Query("DELETE FROM spam_list_extended")
    abstract suspend fun deleteAll(): Int

    /** Number of entries. */
    @Query("SELECT COUNT(*) FROM spam_list_extended")
    abstract suspend fun count(): Int

    /** Replaces the whole list with [entries], atomically. */
    @Transaction
    open suspend fun replaceAll(entries: List<ExtendedSpamListEntry>) {
        deleteAll()
        entries.chunked(WRITE_CHUNK).forEach { upsertAll(it) }
    }

    private companion object {
        const val WRITE_CHUNK = 500
    }
}
