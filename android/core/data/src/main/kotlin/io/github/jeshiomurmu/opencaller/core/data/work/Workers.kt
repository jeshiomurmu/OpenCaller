package io.github.jeshiomurmu.opencaller.core.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.reports.ReportUploader
import io.github.jeshiomurmu.opencaller.core.data.reports.UploadOutcome
import io.github.jeshiomurmu.opencaller.core.data.sync.SpamListSyncRepository
import timber.log.Timber

/**
 * Daily (and "Update now") spam-list update. Network errors retry with backoff; a rejected download (checksum or
 * format) waits for the next scheduled run so a broken file isn't fetched again and again. The old list stays either way.
 */
@HiltWorker
class SpamListSyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val sync: SpamListSyncRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            when (val result = sync.sync()) {
                is AppResult.Success -> {
                    Timber.i("Spam list update: %s", result.value)
                    Result.success()
                }

                is AppResult.Failure -> {
                    Timber.w("Spam list update failed: %s", result.error)
                    if (result.error == AppError.Network && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
                }
            }

        private companion object {
            const val MAX_RETRIES = 10
        }
    }

/** Sends queued reports (TC-REP-001: queued offline, sent when online). */
@HiltWorker
class ReportUploadWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val uploader: ReportUploader,
        private val work: BackgroundWork,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            when (val outcome = uploader.upload()) {
                is UploadOutcome.Done -> {
                    Timber.i("Reports sent: %d", outcome.sent)
                    Result.success()
                }

                UploadOutcome.WaitingForSignIn -> {
                    Result.success()
                }

                is UploadOutcome.RetryLater -> {
                    val after = outcome.afterMillis
                    if (after != null) {
                        // The server's limit resets at a known time: start a new upload then instead of backing off.
                        work.uploadReports(delayMillis = after)
                        Result.success()
                    } else {
                        Result.retry()
                    }
                }
            }
    }
