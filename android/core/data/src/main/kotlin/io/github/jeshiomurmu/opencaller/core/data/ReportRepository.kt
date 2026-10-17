package io.github.jeshiomurmu.opencaller.core.data

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.data.work.BackgroundWork
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.ReportOutboxEntity
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ReportLabel
import io.github.jeshiomurmu.opencaller.core.model.SyncState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Report categories (the server's whitelist, backend `REPORT_CATEGORIES`); apps show translated names. Spam
 * reports only.
 */
val REPORT_CATEGORIES: List<String> =
    listOf("scam", "fraud", "telemarketing", "loan", "debt_collector", "robocall", "survey", "political", "charity", "nuisance", "other")

/** Longest report comment the server accepts. */
const val MAX_REPORT_COMMENT = 280

/**
 * Spam / not-spam reports (docs/architecture.md §4.8). A report is queued in the outbox and sent by
 * [io.github.jeshiomurmu.opencaller.core.data.work.ReportUploadWorker] when the phone is online and the user is
 * signed in; it contains only the reported number, the label, an optional category and comment (§10, invariant 4).
 */
@Singleton
class ReportRepository
    @Inject
    constructor(
        access: DatabaseAccess,
        dispatchers: DispatcherProvider,
        private val clock: Clock,
        private val work: BackgroundWork,
    ) {
        private val storage = Storage(access, dispatchers)

        /**
         * Queues a [label] report for [number] and starts an upload; returns the outbox id. An unknown [category] or a
         * category on a not-spam report is dropped, and [comment] is trimmed to [MAX_REPORT_COMMENT] characters.
         */
        suspend fun queue(
            number: PhoneNumber,
            label: ReportLabel,
            category: String? = null,
            comment: String? = null,
        ): AppResult<Long> =
            storage
                .run {
                    reportOutboxDao().insert(
                        ReportOutboxEntity(
                            e164 = number.e164,
                            label = label,
                            category = category?.takeIf { label == ReportLabel.SPAM && it in REPORT_CATEGORIES },
                            comment =
                                comment
                                    ?.filterNot { it.isISOControl() && it != '\n' }
                                    ?.trim()
                                    ?.take(
                                        MAX_REPORT_COMMENT,
                                    )?.ifEmpty { null },
                            createdAt = clock.nowMillis(),
                            attempts = 0,
                            state = SyncState.PENDING,
                        ),
                    )
                }.also { if (it is AppResult.Success) work.uploadReports() }

        /** Reports waiting to be sent, oldest first. */
        suspend fun pending(limit: Int): AppResult<List<Pair<PhoneNumber, ReportLabel>>> =
            storage.run {
                reportOutboxDao()
                    .inState(SyncState.PENDING, limit)
                    .filter { PhoneNumber.isE164(it.e164) }
                    .map { PhoneNumber(it.e164) to it.label }
            }

        /** Number of reports waiting to be sent. */
        suspend fun pendingCount(): AppResult<Int> = storage.run { reportOutboxDao().countInState(SyncState.PENDING) }

        /** Observes whether reports are waiting (e.g. for "Sign in to share your reports"). */
        fun hasPending(): Flow<AppResult<Boolean>> =
            storage.observe { reportOutboxDao().observeCount(SyncState.PENDING) }.map { result -> result.map { it > 0 } }
    }
