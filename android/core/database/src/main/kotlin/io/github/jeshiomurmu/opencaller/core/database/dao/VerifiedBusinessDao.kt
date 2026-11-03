package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.jeshiomurmu.opencaller.core.database.entity.VerifiedBusinessEntity

/** The verified-business directory on the phone. */
@Dao
interface VerifiedBusinessDao {
    /** Inserts or replaces [rows]. */
    @Upsert
    suspend fun upsertAll(rows: List<VerifiedBusinessEntity>)

    /** Removes the rows of [numbers] (businesses the server revoked). */
    @Query("DELETE FROM verified_business WHERE e164 IN (:numbers)")
    suspend fun deleteAll(numbers: List<String>)

    /** Removes every row (the phone moved to another country). */
    @Query("DELETE FROM verified_business")
    suspend fun clear()

    /** Logo files the rows refer to. */
    @Query("SELECT DISTINCT logoFile FROM verified_business WHERE logoFile IS NOT NULL")
    suspend fun logoFiles(): List<String>

    /** The verified business behind [e164], or `null`. */
    @Query("SELECT * FROM verified_business WHERE e164 = :e164")
    suspend fun find(e164: String): VerifiedBusinessEntity?

    /** Rows whose logo isn't on the phone yet, at most [limit]. */
    @Query("SELECT * FROM verified_business WHERE logoPath IS NOT NULL AND logoFile IS NULL LIMIT :limit")
    suspend fun missingLogos(limit: Int): List<VerifiedBusinessEntity>

    /** Records that business [businessId]'s logo is stored as [file]. */
    @Query("UPDATE verified_business SET logoFile = :file WHERE businessId = :businessId")
    suspend fun setLogoFile(
        businessId: String,
        file: String,
    )
}
