package io.github.jeshiomurmu.opencaller.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import io.github.jeshiomurmu.opencaller.core.database.entity.ReportOutboxEntity
import io.github.jeshiomurmu.opencaller.core.model.SyncState

/** Reports waiting to be uploaded. */
@Dao
interface ReportOutboxDao {
    /** Queues a report and returns its id. */
    @Insert
    suspend fun insert(report: ReportOutboxEntity): Long

    /** Up to [limit] reports in [state], oldest first. */
    @Query("SELECT * FROM report_outbox WHERE state = :state ORDER BY createdAt ASC, id ASC LIMIT :limit")
    suspend fun inState(
        state: SyncState,
        limit: Int,
    ): List<ReportOutboxEntity>

    /** Records an upload attempt for report [id]. */
    @Query("UPDATE report_outbox SET state = :state, attempts = :attempts WHERE id = :id")
    suspend fun updateState(
        id: Long,
        state: SyncState,
        attempts: Int,
    )

    /** Removes the reports with [ids]; returns the number removed. */
    @Query("DELETE FROM report_outbox WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>): Int

    /** Observes the number of reports in [state]. */
    @Query("SELECT COUNT(*) FROM report_outbox WHERE state = :state")
    fun observeCount(state: SyncState): kotlinx.coroutines.flow.Flow<Int>

    /** Number of reports in [state]. */
    @Query("SELECT COUNT(*) FROM report_outbox WHERE state = :state")
    suspend fun countInState(state: SyncState): Int

    /** Puts reports left in [from] (e.g. a send interrupted by process death) back into [to]. */
    @Query("UPDATE report_outbox SET state = :to WHERE state = :from")
    suspend fun moveState(
        from: SyncState,
        to: SyncState,
    ): Int

    /** Removes reports in [state] (e.g. delivered ones); returns the number removed. */
    @Query("DELETE FROM report_outbox WHERE state = :state")
    suspend fun deleteInState(state: SyncState): Int
}
