package io.github.jeshiomurmu.opencaller.feature.messages.ui

import androidx.core.net.toUri
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.model.MessageKey
import io.github.jeshiomurmu.opencaller.core.model.MessagingSims
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.MessagingSimSource
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.StoredMessage
import io.github.jeshiomurmu.opencaller.feature.messages.send.MmsDownloader
import io.github.jeshiomurmu.opencaller.feature.messages.send.OutgoingMessages
import io.github.jeshiomurmu.opencaller.feature.messages.work.MessageScheduling
import io.github.jeshiomurmu.opencaller.feature.messages.work.ScheduledSending
import java.time.ZoneId
import javax.inject.Inject

/** [MessageSending] with [OutgoingMessages] and [MmsDownloader]. */
class DeviceMessageSending
    @Inject
    constructor(
        private val outgoing: OutgoingMessages,
        private val downloader: MmsDownloader,
        private val simSource: MessagingSimSource,
        private val scheduling: ScheduledSending,
        private val clock: Clock,
    ) : MessageSending,
        MessageScheduling by scheduling {
        override fun sims(): MessagingSims = simSource.sims()

        override suspend fun send(
            recipients: List<String>,
            text: String,
            pictures: List<String>,
            subscriptionId: Int?,
        ): AppResult<StoredMessage> = outgoing.send(recipients, text, pictures.map { it.toUri() }, subscriptionId)

        override suspend fun retry(key: MessageKey): AppResult<Unit> = outgoing.retry(key).map { }

        override suspend fun download(id: Long): AppResult<Unit> = downloader.download(id)

        override fun schedulePresets(): List<SchedulePreset> = schedulePresets(clock.nowMillis(), ZoneId.systemDefault())
    }
