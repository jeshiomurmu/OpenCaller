package io.github.jeshiomurmu.opencaller.core.data.reports

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.Storage
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.ReportOutboxEntity
import io.github.jeshiomurmu.opencaller.core.model.ReportLabel
import io.github.jeshiomurmu.opencaller.core.model.SyncState
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerApi
import io.github.jeshiomurmu.opencaller.core.network.ReportBatchDto
import io.github.jeshiomurmu.opencaller.core.network.ReportItemDto
import io.github.jeshiomurmu.opencaller.core.network.TokenStore
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** What one upload run achieved. */
sealed interface UploadOutcome {
    /** Every queued report was sent (or none was waiting); [sent] counts them. */
    data class Done(
        val sent: Int,
    ) : UploadOutcome

    /** Reports wait for the user to sign in (or for a build with a server). */
    data object WaitingForSignIn : UploadOutcome

    /** Try again later: offline or a server error ([afterMillis] = server's `Retry-After`, else backoff). */
    data class RetryLater(
        val afterMillis: Long?,
    ) : UploadOutcome
}

/**
 * Sends the report outbox in batches of 50 (docs/architecture.md §4.8). Delivered reports are deleted. A batch the
 * server rejects as invalid is marked failed so it can't block the queue; network and server errors count an
 * attempt, and a report is given up after [MAX_ATTEMPTS] (§11). The server's daily limit (429) waits for its
 * `Retry-After`.
 */
@Singleton
class ReportUploader
    @Inject
    constructor(
        private val api: OpenCallerApi,
        private val tokens: TokenStore,
        access: DatabaseAccess,
        dispatchers: DispatcherProvider,
    ) {
        private val storage = Storage(access, dispatchers)

        /** Sends everything that is queued. */
        suspend fun upload(): UploadOutcome {
            if (tokens.read() == null) return UploadOutcome.WaitingForSignIn
            // A send interrupted by process death left its batch in flight; it is sent again (reports are idempotent
            // per reporter and number on the server).
            storage.run { reportOutboxDao().moveState(SyncState.IN_FLIGHT, SyncState.PENDING) }
            var sent = 0
            var outcome: UploadOutcome? = null
            while (outcome == null) {
                val reports = (storage.run { reportOutboxDao().inState(SyncState.PENDING, BATCH) } as? AppResult.Success)?.value
                outcome =
                    when {
                        reports == null -> UploadOutcome.RetryLater(null)
                        reports.isEmpty() -> UploadOutcome.Done(sent)
                        else -> send(reports).also { if (it == null) sent += reports.size }
                    }
            }
            return outcome
        }

        // Returns null when the batch is done with (sent or rejected), else why to stop.
        private suspend fun send(reports: List<ReportOutboxEntity>): UploadOutcome? {
            val ids = reports.map { it.id }
            setState(reports, SyncState.IN_FLIGHT)
            val result = apiCall { api.report(ReportBatchDto(reports.map { it.toDto() })) }
            return when (result) {
                is AppResult.Success -> {
                    storage.run { reportOutboxDao().deleteIds(ids) }
                    null
                }

                is AppResult.Failure -> {
                    failed(reports, result.error)
                }
            }
        }

        private suspend fun failed(
            reports: List<ReportOutboxEntity>,
            error: AppError,
        ): UploadOutcome? =
            when (error) {
                is AppError.Validation -> {
                    Timber.w("Server rejected %d report(s): %s", reports.size, error.message)
                    setState(reports, SyncState.FAILED)
                    null
                }

                AppError.Auth, AppError.ServerNotConfigured -> {
                    setState(reports, SyncState.PENDING)
                    UploadOutcome.WaitingForSignIn
                }

                is AppError.RateLimited -> {
                    setState(reports, SyncState.PENDING)
                    UploadOutcome.RetryLater(error.retryAfterSeconds?.times(MILLIS_PER_SECOND))
                }

                else -> {
                    storage.run {
                        reports.forEach { r ->
                            val attempts = r.attempts + 1
                            reportOutboxDao().updateState(
                                r.id,
                                if (attempts >=
                                    MAX_ATTEMPTS
                                ) {
                                    SyncState.FAILED
                                } else {
                                    SyncState.PENDING
                                },
                                attempts,
                            )
                        }
                    }
                    UploadOutcome.RetryLater(null)
                }
            }

        private suspend fun setState(
            reports: List<ReportOutboxEntity>,
            state: SyncState,
        ) {
            storage.run { reports.forEach { reportOutboxDao().updateState(it.id, state, it.attempts) } }
        }

        private fun ReportOutboxEntity.toDto() =
            ReportItemDto(
                e164 = e164,
                label = if (label == ReportLabel.SPAM) "spam" else "not_spam",
                category = category,
                comment = comment,
            )

        /** Limits. */
        companion object {
            /** Reports per request (the server's maximum). */
            const val BATCH = 50

            /** Attempts before a report is given up (docs/architecture.md §11). */
            const val MAX_ATTEMPTS = 10
            private const val MILLIS_PER_SECOND = 1_000L
        }
    }
