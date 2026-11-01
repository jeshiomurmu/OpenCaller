package io.github.jeshiomurmu.opencaller.feature.messages.ui

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.MessageKey
import io.github.jeshiomurmu.opencaller.core.model.MessagingSims
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.StoredMessage
import io.github.jeshiomurmu.opencaller.feature.messages.work.MessageScheduling

/**
 * What the thread screen sends with (pictures are `content://` addresses from the photo picker), including messages
 * scheduled for later.
 */
interface MessageSending : MessageScheduling {
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

    /** The times offered for scheduling now. */
    fun schedulePresets(): List<SchedulePreset>
}
