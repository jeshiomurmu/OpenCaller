package io.github.jeshiomurmu.opencaller.feature.messages.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.data.ScheduledSmsRepository
import io.github.jeshiomurmu.opencaller.core.model.ScheduledMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scheduled SMS on WorkManager (docs/architecture.md §2: background work): one unique work per message, started
 * after the delay. WorkManager runs it at about that time; Doze can hold it back for a few minutes, so a scheduled
 * SMS is not an alarm (no exact alarms: they need a permission Play limits to clocks and calendars).
 */
@Singleton
class ScheduledSending
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val repository: ScheduledSmsRepository,
        private val clock: Clock,
    ) : MessageScheduling {
        private val workManager get() = WorkManager.getInstance(context)

        override suspend fun schedule(
            to: String,
            text: String,
            atMillis: Long,
            subscriptionId: Int?,
        ): AppResult<Long> {
            val stored = repository.schedule(to, text, atMillis, subscriptionId, ::workName)
            if (stored is AppResult.Success) {
                val request =
                    OneTimeWorkRequestBuilder<ScheduledSmsWorker>()
                        .setInitialDelay((atMillis - clock.nowMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                        .setInputData(workDataOf(KEY_ID to stored.value))
                        .build()
                workManager.enqueueUniqueWork(workName(stored.value), ExistingWorkPolicy.REPLACE, request)
            }
            return stored
        }

        override suspend fun cancel(id: Long): AppResult<Unit> {
            workManager.cancelUniqueWork(workName(id))
            return repository.cancel(id)
        }

        override fun pending(): Flow<List<ScheduledMessage>> =
            repository.observePending().map { (it as? AppResult.Success)?.value.orEmpty() }

        /** Keeps the OTP auto-delete check scheduled (every 6 hours; the worker does nothing while it is off). */
        fun scheduleCodeCleanup() {
            val request = PeriodicWorkRequestBuilder<OtpCleanupWorker>(CLEANUP_HOURS, TimeUnit.HOURS).build()
            workManager.enqueueUniquePeriodicWork(CODE_CLEANUP, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        private fun workName(id: Long) = "scheduled-sms-$id"

        /** Work input and names. */
        companion object {
            /** The scheduled message's id. */
            const val KEY_ID = "id"

            /** OTP auto-delete. */
            const val CODE_CLEANUP = "otp-cleanup"
            private const val CLEANUP_HOURS = 6L
        }
    }
