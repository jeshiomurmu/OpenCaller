package io.github.jeshiomurmu.opencaller.core.data.recording

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Saves finished recordings in the background (a `recording_ready` push, or the app catching up). */
fun interface RecordingDownloads {
    /** Downloads every recording still on the server as soon as the phone is online. */
    fun syncSoon()
}

/** [RecordingDownloads] on WorkManager: one unique sync at a time, retried while the network fails. */
@Singleton
class WorkManagerRecordingDownloads
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : RecordingDownloads {
        override fun syncSoon() {
            val request =
                OneTimeWorkRequestBuilder<RecordingSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
            // APPEND_OR_REPLACE: a push arriving during a sync runs another one after it.
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        private companion object {
            const val WORK = "recording-sync"
            const val BACKOFF_SECONDS = 30L
        }
    }

/** Saves the recordings still on the server (TC-REC-001: the file ends up on the phone). */
@HiltWorker
class RecordingSyncWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val recordings: CallRecordings,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            when (val result = recordings.syncAll()) {
                is AppResult.Success -> Result.success()
                is AppResult.Failure -> if (result.error.isTransient()) Result.retry() else Result.success()
            }

        private fun AppError.isTransient(): Boolean =
            this == AppError.Network || this is AppError.RateLimited || this is AppError.Unexpected
    }
