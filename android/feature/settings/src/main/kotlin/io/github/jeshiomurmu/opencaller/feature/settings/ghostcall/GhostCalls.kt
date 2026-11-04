package io.github.jeshiomurmu.opencaller.feature.settings.ghostcall

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.data.supporter.EntitlementProvider
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Schedules the fake call (docs/prd.md F-UT-01). */
interface GhostCalls {
    /** Rings the Ghost Call set up in settings after [delaySeconds]; replaces one that is already waiting. */
    fun schedule(delaySeconds: Int)

    /** Cancels a Ghost Call that hasn't rung yet. */
    fun cancel()
}

/**
 * [GhostCalls] on WorkManager: one unique job that waits the delay, then posts the fake call's notification. It
 * never touches Telecom: no call is placed, registered or shown in the call log. WorkManager may ring a few seconds
 * late in Doze (exact alarms need a permission Play reserves for clocks and calendars).
 */
class WorkManagerGhostCalls
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : GhostCalls {
        override fun schedule(delaySeconds: Int) {
            val request = OneTimeWorkRequestBuilder<GhostCallWorker>().setInitialDelay(delaySeconds.toLong(), TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        override fun cancel() {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private companion object {
            const val WORK_NAME = "ghost_call"
        }
    }

/** Rings the Ghost Call: posts its notification, which opens the fake incoming-call screen (supporters only). */
@HiltWorker
class GhostCallWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val settings: UserSettingsDataSource,
        private val entitlements: EntitlementProvider,
        private val notifier: GhostCallNotifier,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (!entitlements.current().entitled) {
                Timber.i("Ghost Call not rung: no supporter entitlement")
                return Result.success()
            }
            notifier.ring(settings.preferences.first().ghostCall)
            return Result.success()
        }
    }
