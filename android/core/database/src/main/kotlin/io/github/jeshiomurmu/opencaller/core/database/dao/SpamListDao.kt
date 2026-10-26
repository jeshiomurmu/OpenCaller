package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.jeshiomurmu.opencaller.core.database.entity.SpamListEntry

/** On-device spam list. */
@Dao
abstract class SpamListDao {
    /** Inserts or replaces [entries]. */
    @Upsert
    abstract suspend fun upsertAll(entries: List<SpamListEntry>)

    /** The entry for [e164], or `null` if the number is not listed. */
    @Query("SELECT * FROM spam_list WHERE e164 = :e164")
    abstract suspend fun find(e164: String): SpamListEntry?

    /** Removes entries from list versions older than [version]; returns the number removed. */
    @Query("DELETE FROM spam_list WHERE listVersion < :version")
    abstract suspend fun deleteOlderThan(version: Long): Int

    /** Deletes every entry; returns the number removed. */
    @Query("DELETE FROM spam_list")
    abstract suspend fun deleteAll(): Int

    /** Removes the listed [numbers] (E.164); returns the number removed. */
    @Query("DELETE FROM spam_list WHERE e164 IN (:numbers)")
    abstract suspend fun deleteNumbers(numbers: List<String>): Int

    /** Marks every entry as part of list [version]. */
    @Query("UPDATE spam_list SET listVersion = :version")
    abstract suspend fun setVersion(version: Long): Int

    /** Number of listed numbers. */
    @Query("SELECT COUNT(*) FROM spam_list")
    abstract suspend fun count(): Int

    /** Replaces the whole list with [entries], atomically (a failure leaves the old list in place). */
    @Transaction
    open suspend fun replaceAll(entries: List<SpamListEntry>) {
        deleteAll()
        entries.chunked(WRITE_CHUNK).forEach { upsertAll(it) }
    }

    /**
     * Applies deltas in order, atomically: for each, deletes its removals and inserts or replaces its entries; then
     * marks the whole list as [version], so the result equals the full list of that version row for row. A failure
     * in any step leaves the list exactly as it was.
     */
    @Transaction
    open suspend fun applyDeltas(
        deltas: List<Pair<List<SpamListEntry>, List<String>>>,
        version: Long,
    ) {
        deltas.forEach { (upserts, removals) ->
            removals.chunked(WRITE_CHUNK).forEach { deleteNumbers(it) }
            upserts.chunked(WRITE_CHUNK).forEach { upsertAll(it) }
        }
        setVersion(version)
    }

    private companion object {
        // Stays below SQLite's bound-variable limit (999 on older devices) for IN lists and multi-row inserts.
        const val WRITE_CHUNK = 500
    }
}
