package io.github.jeshiomurmu.opencaller.feature.messages.send

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.model.MmsDownload
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.DeviceMmsStore
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.MessagingSimSource
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.PendingDownload
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.StoredMessage
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.MalformedPduException
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.MmsPduParser
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms.RetrieveConf
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads received MMS (TC-SMS-003): `SmsManager.downloadMultimediaMessage` fetches the message from the carrier
 * into a [MmsPduFiles] file; when it is there the message replaces its notice in the store and the carrier is told
 * (M-NotifyResp.ind). Messages are downloaded as soon as the notice arrives, and again when the user taps Download.
 */
@Singleton
class MmsDownloader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: DeviceMmsStore,
        private val files: MmsPduFiles,
        private val sender: MmsSender,
        private val sims: MessagingSimSource,
    ) {
        /** Starts downloading notice [noticeId]. */
        suspend fun download(noticeId: Long): AppResult<Unit> =
            store
                .pendingDownload(noticeId)
                .flatMap { it?.let { pending -> AppResult.Success(pending) } ?: AppResult.Failure(AppError.NotFound) }
                .flatMap { pending -> start(pending) }

        /**
         * The download of notice [noticeId] into [file] ended ([succeeded] is the MMS service's result): stores the
         * message and answers the carrier, or marks the notice failed so the user can try again.
         */
        suspend fun downloaded(
            noticeId: Long,
            file: String,
            subscriptionId: Int?,
            succeeded: Boolean,
        ): AppResult<StoredMessage> {
            val bytes = if (succeeded) readAndDelete(file) else null.also { files.delete(file) }
            val message = bytes?.let(::parse)
            val pending = store.pendingDownload(noticeId)
            val stored =
                if (message == null) {
                    AppResult.Failure(AppError.Validation("download failed"))
                } else {
                    store.saveRetrieved(noticeId, message, subscriptionId, sims.ownNumbers())
                }
            if (stored is AppResult.Success) {
                (pending as? AppResult.Success)?.value?.transactionId?.let { sender.answerDownload(it, subscriptionId) }
            } else {
                store.setDownload(noticeId, MmsDownload.FAILED)
            }
            return stored
        }

        private suspend fun start(pending: PendingDownload): AppResult<Unit> {
            val manager =
                context.smsManagerFor(pending.subscriptionId) ?: return AppResult.Failure(AppError.Validation("no SMS on this device"))
            store.setDownload(pending.id, MmsDownload.DOWNLOADING)
            val started =
                try {
                    val file = files.create()
                    val done = MessagingIntents.mmsDownloaded(context, pending.id, file, pending.subscriptionId)
                    manager.downloadMultimediaMessage(context, pending.contentLocation, files.uriOf(file), null, done)
                    AppResult.Success(Unit)
                } catch (e: IOException) {
                    Timber.w(e, "MMS download file not created")
                    AppResult.Failure(AppError.Storage)
                } catch (e: IllegalArgumentException) {
                    Timber.w(e, "MMS download refused")
                    AppResult.Failure(AppError.Validation("refused"))
                }
            if (started is AppResult.Failure) store.setDownload(pending.id, MmsDownload.FAILED)
            return started
        }

        private fun readAndDelete(file: String): ByteArray? =
            try {
                files.read(file).takeIf(ByteArray::isNotEmpty)
            } catch (e: IOException) {
                Timber.w(e, "Downloaded MMS not readable")
                null
            } finally {
                files.delete(file)
            }

        private fun parse(bytes: ByteArray): RetrieveConf? =
            try {
                MmsPduParser.parse(bytes) as? RetrieveConf
            } catch (e: MalformedPduException) {
                Timber.w(e, "Downloaded MMS not readable")
                null
            }
    }
