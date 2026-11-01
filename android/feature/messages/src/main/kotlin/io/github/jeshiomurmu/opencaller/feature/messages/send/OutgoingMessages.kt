package io.github.jeshiomurmu.opencaller.feature.messages.send

import android.net.Uri
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.model.MessageKey
import io.github.jeshiomurmu.opencaller.core.model.MessageKind
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.ConversationSource
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.StoredMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends what the user wrote the right way: an SMS to one person, an MMS when there are pictures or several people
 * (a group message, so everyone sees the replies).
 */
@Singleton
class OutgoingMessages
    @Inject
    constructor(
        private val sms: SmsSender,
        private val mms: MmsSender,
        private val images: ImageScaler,
        private val conversations: ConversationSource,
    ) {
        /** Sends [text] and [pictures] to [recipients] with SIM [subscriptionId] (`null`: the default SMS SIM). */
        suspend fun send(
            recipients: List<String>,
            text: String,
            pictures: List<Uri>,
            subscriptionId: Int?,
        ): AppResult<StoredMessage> =
            when {
                recipients.isEmpty() -> {
                    AppResult.Failure(AppError.Validation("no recipients"))
                }

                text.isBlank() && pictures.isEmpty() -> {
                    AppResult.Failure(AppError.Validation("empty message"))
                }

                pictures.isEmpty() && recipients.size == 1 -> {
                    sms.send(recipients.single(), text, subscriptionId)
                }

                else -> {
                    images
                        .fit(pictures, mms.maxMessageBytes(subscriptionId))
                        .flatMap { mms.send(recipients, text.takeIf(String::isNotBlank), it, subscriptionId) }
                }
            }

        /** Sends [text] to the people of thread [threadId] (a reply from the notification or the thread). */
        suspend fun reply(
            threadId: Long,
            text: String,
            subscriptionId: Int?,
        ): AppResult<StoredMessage> =
            conversations.participants(threadId).flatMap { people ->
                send(people.map { it.address }, text, emptyList(), subscriptionId)
            }

        /** Sends failed message [key] again (SMS only; a failed MMS is written again). */
        suspend fun retry(key: MessageKey): AppResult<StoredMessage> =
            if (key.kind ==
                MessageKind.SMS
            ) {
                sms.resend(key.id)
            } else {
                AppResult.Failure(AppError.Validation("MMS can't be resent"))
            }
    }
