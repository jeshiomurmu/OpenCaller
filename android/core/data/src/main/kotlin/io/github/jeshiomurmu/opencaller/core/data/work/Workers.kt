package io.github.jeshiomurmu.opencaller.core.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.business.BusinessDirectoryRepository
import io.github.jeshiomurmu.opencaller.core.data.reports.ReportUploader
import io.github.jeshiomurmu.opencaller.core.data.reports.UploadOutcome
import io.github.jeshiomurmu.opencaller.core.data.supporter.EntitlementProvider
import io.github.jeshiomurmu.opencaller.core.data.sync.RegistrySyncRepository
import io.github.jeshiomurmu.opencaller.core.data.sync.SpamListSyncRepository
import timber.log.Timber

/**
 * Daily (and "Update now") update of the server's public data: the user's entitlements, the spam list (with the
 * extended list for supporters), then the registries (sender IDs, OTP
 * sources, known-bad link prefixes) and the verified-business directory with its logos. Network errors retry with
 * backoff; a rejected download (checksum or format) waits for the next scheduled run so a broken file isn't fetched
 * again and again. The old data stays either way.
 */
@HiltWorker
class SpamListSyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val sync: SpamListSyncRepository,
        private val registries: RegistrySyncRepository,
        private val businesses: BusinessDirectoryRepository,
        private val entitlements: EntitlementProvider,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            // Entitlements first: they decide whether the extended spam list is downloaded or dropped.
            val entitlement = entitlements.refresh()
            if (entitlement is AppResult.Failure) Timber.w("Entitlements not refreshed: %s", entitlement.error)
            val list = sync.sync()
            val registry = registries.sync()
            val directory = businesses.sync()
            Timber.i("Spam list update: %s; registries: %s; businesses: %s", list, registry, directory)
            val networkFailed = listOf(list, registry, directory).any { it is AppResult.Failure && it.error == AppError.Network }
            return if (networkFailed && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
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
