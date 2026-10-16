package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.jeshiomurmu.opencaller.core.database.entity.NumberInfoEntity

/** Reputation cache. */
@Dao
interface NumberInfoDao {
    /** Inserts or replaces [info]. */
    @Upsert
    suspend fun upsert(info: NumberInfoEntity)

    /** Cached info for [e164] that has not expired at [nowMillis], or `null`. */
    @Query("SELECT * FROM number_info WHERE e164 = :e164 AND expiresAt > :nowMillis")
    suspend fun findFresh(
        e164: String,
        nowMillis: Long,
    ): NumberInfoEntity?

    /** Removes entries expired at [nowMillis]; returns the number removed. */
    @Query("DELETE FROM number_info WHERE expiresAt <= :nowMillis")
    suspend fun deleteExpired(nowMillis: Long): Int
}
