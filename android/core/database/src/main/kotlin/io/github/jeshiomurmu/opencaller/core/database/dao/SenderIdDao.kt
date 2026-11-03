package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.jeshiomurmu.opencaller.core.database.entity.SenderIdEntity

/** Sender-ID registry rows. */
@Dao
interface SenderIdDao {
    /** Inserts or replaces [rows]. */
    @Upsert
    suspend fun upsertAll(rows: List<SenderIdEntity>)

    /** Removes [headers] of [countryIso] (rows the registry no longer lists). */
    @Query("DELETE FROM sender_id WHERE countryIso = :countryIso AND header IN (:headers)")
    suspend fun deleteAll(
        countryIso: String,
        headers: List<String>,
    )

    /** The registry row for [header] in [countryIso], or `null`. */
    @Query("SELECT * FROM sender_id WHERE countryIso = :countryIso AND header = :header")
    suspend fun find(
        countryIso: String,
        header: String,
    ): SenderIdEntity?
}
