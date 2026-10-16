package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import io.github.jeshiomurmu.opencaller.core.database.entity.BlockRuleEntity
import kotlinx.coroutines.flow.Flow

/** User block rules. */
@Dao
interface BlockRuleDao {
    /** Saves a new rule and returns its id. */
    @Insert
    suspend fun insert(rule: BlockRuleEntity): Long

    /** Updates an existing rule. */
    @Update
    suspend fun update(rule: BlockRuleEntity)

    /** Deletes a rule. */
    @Delete
    suspend fun delete(rule: BlockRuleEntity)

    /** All rules, newest first. */
    @Query("SELECT * FROM block_rule ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<BlockRuleEntity>>

    /** Enabled rules, used by the screening engine. */
    @Query("SELECT * FROM block_rule WHERE enabled = 1")
    suspend fun enabledRules(): List<BlockRuleEntity>

    /** The rule with [id], or `null`. */
    @Query("SELECT * FROM block_rule WHERE id = :id")
    suspend fun find(id: Long): BlockRuleEntity?

    /** Exact-number rules for [e164], enabled or not (used by Undo). */
    @Query("SELECT * FROM block_rule WHERE type = 'EXACT' AND pattern = :e164")
    suspend fun exactRulesFor(e164: String): List<BlockRuleEntity>

    /** Deletes the rule with [id]; returns the number removed. */
    @Query("DELETE FROM block_rule WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /** Turns the rule with [id] on or off; returns the number changed. */
    @Query("UPDATE block_rule SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(
        id: Long,
        enabled: Boolean,
    ): Int

    /** Deletes every rule; returns the number removed. */
    @Query("DELETE FROM block_rule")
    suspend fun deleteAll(): Int
}
