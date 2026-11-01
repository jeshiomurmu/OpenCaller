package io.github.jeshiomurmu.opencaller.feature.messages.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.data.ScheduledSmsRepository
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.model.SyncState
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.ConversationSource
import io.github.jeshiomurmu.opencaller.feature.messages.notify.MessageNotifier
import io.github.jeshiomurmu.opencaller.feature.messages.send.OutgoingMessages
import io.github.jeshiomurmu.opencaller.feature.messages.ui.InboxCleaner
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Sends one scheduled SMS (id [ScheduledSending.KEY_ID]) when its time comes. A message the user cancelled, or one
 * already sent, is skipped. A failure is not retried (the user would get it late without knowing): the row is marked
 * failed and the "not sent" notification shows.
 */
@HiltWorker
class ScheduledSmsWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val scheduled: ScheduledSmsRepository,
        private val outgoing: OutgoingMessages,
        private val notifier: MessageNotifier,
        private val conversations: ConversationSource,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val id = inputData.getLong(ScheduledSending.KEY_ID, -1)
            val message = (scheduled.find(id) as? AppResult.Success)?.value
            if (message == null || message.state != SyncState.PENDING) return Result.success()
            scheduled.mark(id, SyncState.IN_FLIGHT)
            val sent = outgoing.send(listOf(message.to), message.body, emptyList(), message.subscriptionId)
            scheduled.mark(id, if (sent is AppResult.Success) SyncState.DONE else SyncState.FAILED)
            if (sent is AppResult.Failure) {
                Timber.w("Scheduled SMS not sent: %s", sent.error)
                conversations.threadFor(setOf(message.to)).getOrNull()?.let(notifier::sendFailed)
            }
            return Result.success()
        }
    }

/** Deletes one-time-code messages older than a day while the user has OTP auto-delete on (runs a few times a day). */
@HiltWorker
class OtpCleanupWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val settings: UserSettingsDataSource,
        private val cleaner: InboxCleaner,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            if (settings.preferences.first().otpAutoDelete) {
                Timber.i("Old codes deleted: %s", cleaner.deleteOldCodes())
            }
            return Result.success()
        }
    }
