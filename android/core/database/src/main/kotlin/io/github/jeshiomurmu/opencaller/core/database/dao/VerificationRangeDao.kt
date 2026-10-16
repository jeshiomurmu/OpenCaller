package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.jeshiomurmu.opencaller.core.database.entity.VerificationRangeEntity

/** Flash-call verification number ranges. */
@Dao
interface VerificationRangeDao {
    /** Inserts or replaces [rows]. */
    @Upsert
    suspend fun upsertAll(rows: List<VerificationRangeEntity>)

    /** The longest stored prefix of [e164], or `null` if no range matches. */
    @Query(
        "SELECT * FROM verification_range WHERE substr(:e164, 1, length(prefix)) = prefix " +
            "ORDER BY length(prefix) DESC LIMIT 1",
    )
    suspend fun findLongestPrefix(e164: String): VerificationRangeEntity?
}
