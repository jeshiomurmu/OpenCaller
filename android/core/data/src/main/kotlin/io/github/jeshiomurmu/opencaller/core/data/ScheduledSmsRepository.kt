package io.github.jeshiomurmu.opencaller.core.data

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.ScheduledSmsEntity
import io.github.jeshiomurmu.opencaller.core.model.ScheduledMessage
import io.github.jeshiomurmu.opencaller.core.model.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scheduled SMS (the `scheduled_sms` table). The message text waits in OpenCaller's encrypted database until it is
 * sent, then the row is marked done; the sent message itself lives in Android's message store like any other.
 */
@Singleton
class ScheduledSmsRepository
    @Inject
    constructor(
        access: DatabaseAccess,
        dispatchers: DispatcherProvider,
    ) {
        private val storage = Storage(access, dispatchers)

        /** Schedules [body] to [to] at [sendAtMillis] and returns its id; [workName] names its WorkManager work. */
        suspend fun schedule(
            to: String,
            body: String,
            sendAtMillis: Long,
            subscriptionId: Int?,
            workName: (Long) -> String,
        ): AppResult<Long> =
            storage.run {
                val dao = scheduledSmsDao()
                val entity =
                    ScheduledSmsEntity(
                        to = to,
                        body = body,
                        subId = subscriptionId ?: NO_SIM,
                        sendAt = sendAtMillis,
                        workId = "",
                        state = SyncState.PENDING,
                    )
                dao.insert(entity).also { id -> dao.updateWork(id, workName(id)) }
            }

        /** Messages waiting to be sent, soonest first. */
        fun observePending(): Flow<AppResult<List<ScheduledMessage>>> =
            storage.observe { scheduledSmsDao().observeInState(SyncState.PENDING) }.map { result ->
                result.map { rows -> rows.map { it.toDomain() } }
            }

        /** Message [id], or `null`. */
        suspend fun find(id: Long): AppResult<ScheduledMessage?> = storage.run { scheduledSmsDao().find(id)?.toDomain() }

        /** Marks message [id] [state]. */
        suspend fun mark(
            id: Long,
            state: SyncState,
        ): AppResult<Unit> = storage.run { scheduledSmsDao().updateState(id, state) }

        /** Forgets message [id] (the user cancelled it). */
        suspend fun cancel(id: Long): AppResult<Unit> = storage.run { scheduledSmsDao().delete(id) }

        private fun ScheduledSmsEntity.toDomain() = ScheduledMessage(id, to, body, sendAt, subId.takeIf { it != NO_SIM }, state)

        private companion object {
            // SubscriptionManager.INVALID_SUBSCRIPTION_ID: no SIM chosen, Android's default SMS SIM is used.
            const val NO_SIM = -1
        }
    }
