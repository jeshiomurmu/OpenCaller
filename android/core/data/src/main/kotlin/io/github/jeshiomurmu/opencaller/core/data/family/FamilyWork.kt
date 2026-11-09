package io.github.jeshiomurmu.opencaller.core.data.family

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.model.FamilyAlertKind
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Background sends of family protection (WorkManager in production, a recorder in tests). */
interface FamilyWork {
    /** Sends an alert of [kind] raised at [atMillis] as soon as the phone is online. */
    fun sendAlert(
        kind: FamilyAlertKind,
        atMillis: Long,
    )

    /** Keeps the daily counts scheduled while [enabled], else cancels them. */
    fun scheduleDailyCounts(enabled: Boolean)
}

/** [FamilyWork] on WorkManager. */
@Singleton
class WorkManagerFamilyWork
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : FamilyWork {
        private val workManager get() = WorkManager.getInstance(context)

        override fun sendAlert(
            kind: FamilyAlertKind,
            atMillis: Long,
        ) {
            val request =
                OneTimeWorkRequestBuilder<FamilyAlertWorker>()
                    .setInputData(workDataOf(KIND to kind.wireName, AT to atMillis))
                    .setConstraints(connected())
                    .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
            workManager.enqueue(request)
        }

        override fun scheduleDailyCounts(enabled: Boolean) {
            if (!enabled) {
                workManager.cancelUniqueWork(DAILY_COUNTS)
                return
            }
            val request =
                PeriodicWorkRequestBuilder<FamilyCountsWorker>(1, TimeUnit.DAYS)
                    .setConstraints(connected())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
            // KEEP: a member changing their label doesn't move the schedule.
            workManager.enqueueUniquePeriodicWork(DAILY_COUNTS, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private fun connected() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Work names and input keys. */
        companion object {
            /** The daily counts. */
            const val DAILY_COUNTS = "family-daily-counts"
            internal const val KIND = "kind"
            internal const val AT = "at"
            private const val BACKOFF_SECONDS = 10L
        }
    }

/** Sends one queued family alert (TC-FAM-002). */
@HiltWorker
class FamilyAlertWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val reporter: FamilyAlertReporter,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val kind = FamilyAlertKind.parse(inputData.getString(WorkManagerFamilyWork.KIND)) ?: return Result.failure()
            val at = inputData.getLong(WorkManagerFamilyWork.AT, 0L)
            return resultOf(reporter.send(kind, at))
        }
    }

/** Sends yesterday's counts once a day (in the phone's own time zone). */
@HiltWorker
class FamilyCountsWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val reporter: FamilyCountsReporter,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result = resultOf(reporter.sendYesterday(ZoneId.systemDefault()))
    }

private fun resultOf(outcome: FamilySendOutcome): ListenableWorker.Result =
    when (outcome) {
        FamilySendOutcome.RETRY -> ListenableWorker.Result.retry()
        FamilySendOutcome.DONE, FamilySendOutcome.DROPPED -> ListenableWorker.Result.success()
    }
