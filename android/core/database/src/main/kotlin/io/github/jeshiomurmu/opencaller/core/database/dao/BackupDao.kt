package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.jeshiomurmu.opencaller.core.database.entity.BlockRuleEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.MessageMetaEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.ScreeningEventEntity

/**
 * What a backup holds of the database (docs/prd.md F-UT-05): the user's block rules, the screening log and the
 * message categories, read whole and restored in one transaction.
 */
@Dao
abstract class BackupDao {
    /** Every block rule. */
    @Query("SELECT * FROM block_rule ORDER BY id")
    abstract suspend fun rules(): List<BlockRuleEntity>

    /** Every screening event. */
    @Query("SELECT * FROM screening_event ORDER BY id")
    abstract suspend fun events(): List<ScreeningEventEntity>

    /** Every message's metadata. */
    @Query("SELECT * FROM message_meta ORDER BY providerId")
    abstract suspend fun messageMeta(): List<MessageMetaEntity>

    /** Deletes every block rule. */
    @Query("DELETE FROM block_rule")
    abstract suspend fun deleteRules(): Int

    /** Deletes the screening log. */
    @Query("DELETE FROM screening_event")
    abstract suspend fun deleteEvents(): Int

    /** Inserts [rules] (new ids). */
    @Insert
    abstract suspend fun insertRules(rules: List<BlockRuleEntity>)

    /** Inserts [events] (new ids). */
    @Insert
    abstract suspend fun insertEvents(events: List<ScreeningEventEntity>)

    /** Inserts or replaces [meta]. */
    @Upsert
    abstract suspend fun upsertMeta(meta: List<MessageMetaEntity>)

    /**
     * Replaces the block rules and the screening log with the backup's, and adds its message metadata; atomic, so a
     * failure leaves everything as it was.
     */
    @Transaction
    open suspend fun restore(
        rules: List<BlockRuleEntity>,
        events: List<ScreeningEventEntity>,
        meta: List<MessageMetaEntity>,
    ) {
        deleteRules()
        rules.chunked(WRITE_CHUNK).forEach { insertRules(it) }
        deleteEvents()
        events.chunked(WRITE_CHUNK).forEach { insertEvents(it) }
        meta.chunked(WRITE_CHUNK).forEach { upsertMeta(it) }
    }

    private companion object {
        const val WRITE_CHUNK = 500
    }
}
