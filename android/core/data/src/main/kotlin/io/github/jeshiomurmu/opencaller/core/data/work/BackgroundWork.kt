package io.github.jeshiomurmu.opencaller.core.data.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Background jobs the app starts (WorkManager in production, a recorder in tests). */
interface BackgroundWork {
    /** Sends queued reports when online, after [delayMillis] (e.g. a server's `Retry-After`). */
    fun uploadReports(delayMillis: Long = 0)

    /** Keeps the daily spam-list update scheduled; [allowMobileData] = any network, else unmetered only. */
    fun scheduleListSync(allowMobileData: Boolean)

    /** Updates the spam list now, on any network. */
    fun syncListNow()
}

/** [BackgroundWork] on WorkManager: unique work names, network constraints and exponential backoff. */
@Singleton
class WorkManagerBackgroundWork
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : BackgroundWork {
        private val workManager get() = WorkManager.getInstance(context)

        override fun uploadReports(delayMillis: Long) {
            val request =
                OneTimeWorkRequestBuilder<ReportUploadWorker>()
                    .setConstraints(connected())
                    .setInitialDelay(delayMillis.coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
            // A delayed retry replaces a waiting one; an upload already running finishes first.
            workManager.enqueueUniqueWork(REPORT_UPLOAD, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        override fun scheduleListSync(allowMobileData: Boolean) {
            val network = if (allowMobileData) NetworkType.CONNECTED else NetworkType.UNMETERED
            val request =
                PeriodicWorkRequestBuilder<SpamListSyncWorker>(1, TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
            // UPDATE keeps the schedule (no extra run) but applies a changed network setting.
            workManager.enqueueUniquePeriodicWork(LIST_SYNC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        override fun syncListNow() {
            val request =
                OneTimeWorkRequestBuilder<SpamListSyncWorker>()
                    .setConstraints(connected())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
            workManager.enqueueUniqueWork(LIST_SYNC_NOW, ExistingWorkPolicy.KEEP, request)
        }

        private fun connected() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Unique work names. */
        companion object {
            /** Report upload. */
            const val REPORT_UPLOAD = "report-upload"

            /** Daily spam-list update. */
            const val LIST_SYNC = "spam-list-sync"

            /** One-off spam-list update ("Update now"). */
            const val LIST_SYNC_NOW = "spam-list-sync-now"

            private const val BACKOFF_SECONDS = 30L
        }
    }
