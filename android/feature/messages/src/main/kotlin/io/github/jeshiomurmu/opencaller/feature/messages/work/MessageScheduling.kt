package io.github.jeshiomurmu.opencaller.feature.messages.work

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.ScheduledMessage
import kotlinx.coroutines.flow.Flow

/** Scheduling messages for later, as the thread screen uses it. */
interface MessageScheduling {
    /** Schedules [text] to [to] at [atMillis] from SIM [subscriptionId]. */
    suspend fun schedule(
        to: String,
        text: String,
        atMillis: Long,
        subscriptionId: Int?,
    ): AppResult<Long>

    /** Cancels scheduled message [id]. */
    suspend fun cancel(id: Long): AppResult<Unit>

    /** Messages waiting to be sent, soonest first. */
    fun pending(): Flow<List<ScheduledMessage>>
}
