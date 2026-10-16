package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.github.jeshiomurmu.opencaller.core.database.entity.FamilyMemberEntity
import kotlinx.coroutines.flow.Flow

/** Family-protection group members. */
@Dao
interface FamilyMemberDao {
    /** Inserts or replaces [members]. */
    @Upsert
    suspend fun upsertAll(members: List<FamilyMemberEntity>)

    /** All members, oldest first. */
    @Query("SELECT * FROM family_member ORDER BY joinedAt ASC")
    fun observeAll(): Flow<List<FamilyMemberEntity>>

    /** Removes everyone (leaving the group). */
    @Query("DELETE FROM family_member")
    suspend fun deleteAll()
}
