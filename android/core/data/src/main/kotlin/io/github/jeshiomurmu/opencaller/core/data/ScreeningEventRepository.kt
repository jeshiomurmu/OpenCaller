package io.github.jeshiomurmu.opencaller.core.data

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.ScreeningEventEntity
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEvent
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map as mapEach

/** The screening log: every decision, for the blocked-calls log and the debug screen. Kept on the device only. */
@Singleton
class ScreeningEventRepository
    @Inject
    constructor(
        access: DatabaseAccess,
        dispatchers: DispatcherProvider,
        private val clock: Clock,
    ) {
        private val storage = Storage(access, dispatchers)

        /** Saves [event] (its id is ignored), drops events older than [RETENTION_MILLIS] and returns the new id. */
        suspend fun record(event: ScreeningEvent): AppResult<Long> =
            storage.run {
                val dao = screeningEventDao()
                val id = dao.insert(event.toEntity().copy(id = 0))
                dao.deleteOlderThan(clock.nowMillis() - RETENTION_MILLIS)
                id
            }

        /** The [limit] most recent events, newest first. */
        fun observeRecent(limit: Int): Flow<AppResult<List<ScreeningEvent>>> =
            storage.observe { screeningEventDao().observeRecent(limit) }.toDomain()

        /** The [limit] most recent blocked or silenced calls, newest first. */
        fun observeBlocked(limit: Int): Flow<AppResult<List<ScreeningEvent>>> =
            storage.observe { screeningEventDao().observeBlocked(limit) }.toDomain()

        /** The [limit] most recent events, newest first. */
        suspend fun recent(limit: Int): AppResult<List<ScreeningEvent>> =
            storage.run { screeningEventDao().recent(limit) }.map { events -> events.map { it.toDomain() } }

        /** The event with [id], or `null`. */
        suspend fun find(id: Long): AppResult<ScreeningEvent?> = storage.run { screeningEventDao().find(id) }.map { it?.toDomain() }

        /** Deletes every event (debug QA reset). */
        suspend fun deleteAll(): AppResult<Int> = storage.run { screeningEventDao().deleteAll() }

        private fun Flow<AppResult<List<ScreeningEventEntity>>>.toDomain(): Flow<AppResult<List<ScreeningEvent>>> =
            mapEach { result -> result.map { events -> events.map { it.toDomain() } } }

        /** How long events are kept. */
        companion object {
            /** 90 days. */
            const val RETENTION_MILLIS: Long = 90L * 24 * 60 * 60 * 1000
        }
    }
