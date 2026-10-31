package io.github.jeshiomurmu.opencaller.feature.messages.ui

import androidx.core.net.toUri
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.model.MessageKey
import io.github.jeshiomurmu.opencaller.core.model.MessagingSims
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.MessagingSimSource
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.StoredMessage
import io.github.jeshiomurmu.opencaller.feature.messages.send.MmsDownloader
import io.github.jeshiomurmu.opencaller.feature.messages.send.OutgoingMessages
import javax.inject.Inject

/** What the thread screen sends with (pictures are `content://` addresses from the photo picker). */
interface MessageSending {
    /** The SIMs to send with. */
    fun sims(): MessagingSims

    /** Sends [text] and [pictures] to [recipients]. */
    suspend fun send(
        recipients: List<String>,
        text: String,
        pictures: List<String>,
        subscriptionId: Int?,
    ): AppResult<StoredMessage>

    /** Sends failed message [key] again. */
    suspend fun retry(key: MessageKey): AppResult<Unit>

    /** Downloads the MMS of notice [id]. */
    suspend fun download(id: Long): AppResult<Unit>
}

/** [MessageSending] with [OutgoingMessages] and [MmsDownloader]. */
class DeviceMessageSending
    @Inject
    constructor(
        private val outgoing: OutgoingMessages,
        private val downloader: MmsDownloader,
        private val simSource: MessagingSimSource,
    ) : MessageSending {
        override fun sims(): MessagingSims = simSource.sims()

        override suspend fun send(
            recipients: List<String>,
            text: String,
            pictures: List<String>,
            subscriptionId: Int?,
        ): AppResult<StoredMessage> = outgoing.send(recipients, text, pictures.map { it.toUri() }, subscriptionId)

        override suspend fun retry(key: MessageKey): AppResult<Unit> = outgoing.retry(key).map { }

        override suspend fun download(id: Long): AppResult<Unit> = downloader.download(id)
    }
