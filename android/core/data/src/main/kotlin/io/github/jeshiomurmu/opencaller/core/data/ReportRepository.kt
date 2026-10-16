package io.github.jeshiomurmu.opencaller.core.data

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.ReportOutboxEntity
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ReportLabel
import io.github.jeshiomurmu.opencaller.core.model.SyncState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spam / not-spam reports. Reports are queued in the outbox; nothing is sent until the sync worker arrives in
 * Phase 5, and a report only ever contains the reported number and the label (docs/architecture.md §10).
 */
@Singleton
class ReportRepository
    @Inject
    constructor(
        access: DatabaseAccess,
        dispatchers: DispatcherProvider,
        private val clock: Clock,
    ) {
        private val storage = Storage(access, dispatchers)

        /** Queues a [label] report for [number] and returns its outbox id. */
        suspend fun queue(
            number: PhoneNumber,
            label: ReportLabel,
        ): AppResult<Long> =
            storage.run {
                reportOutboxDao().insert(
                    ReportOutboxEntity(
                        e164 = number.e164,
                        label = label,
                        category = null,
                        comment = null,
                        createdAt = clock.nowMillis(),
                        attempts = 0,
                        state = SyncState.PENDING,
                    ),
                )
            }

        /** Reports waiting to be sent, oldest first. */
        suspend fun pending(limit: Int): AppResult<List<Pair<PhoneNumber, ReportLabel>>> =
            storage.run {
                reportOutboxDao()
                    .inState(SyncState.PENDING, limit)
                    .filter { PhoneNumber.isE164(it.e164) }
                    .map { PhoneNumber(it.e164) to it.label }
            }
    }
