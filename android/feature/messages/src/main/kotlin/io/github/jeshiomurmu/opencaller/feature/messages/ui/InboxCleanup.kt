package io.github.jeshiomurmu.opencaller.feature.messages.ui

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import kotlinx.coroutines.flow.Flow

/** What the inbox cleaner would delete: how many messages per category, and which. */
data class CleanPlan(
    val counts: Map<MessageCategory, Int>,
    val ids: List<Long>,
) {
    /** How many messages in all. */
    val total: Int get() = ids.size
}

/** The inbox cleaner and the OTP auto-delete setting, as the conversations screen uses them. */
interface InboxCleanup {
    /** What a clean-up would delete now. */
    suspend fun plan(): AppResult<CleanPlan>

    /** Deletes [plan]'s messages; returns how many went. */
    suspend fun clean(plan: CleanPlan): AppResult<Int>

    /** Whether codes are deleted a day after they arrive. */
    fun autoDelete(): Flow<Boolean>

    /** Turns OTP auto-delete on or off. */
    suspend fun setAutoDelete(enabled: Boolean)
}
