package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.jeshiomurmu.opencaller.core.database.entity.OtpAppHashEntity

/** SMS Retriever app hashes. */
@Dao
interface OtpAppHashDao {
    /** Inserts or replaces [rows]. */
    @Upsert
    suspend fun upsertAll(rows: List<OtpAppHashEntity>)

    /** The app owning [hash], or `null`. */
    @Query("SELECT * FROM otp_app_hash WHERE hash = :hash")
    suspend fun find(hash: String): OtpAppHashEntity?
}
