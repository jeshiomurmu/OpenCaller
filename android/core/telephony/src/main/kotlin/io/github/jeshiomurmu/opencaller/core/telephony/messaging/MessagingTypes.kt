package io.github.jeshiomurmu.opencaller.core.telephony.messaging

import io.github.jeshiomurmu.opencaller.core.model.MessageKey

/**
 * An SMS as it arrived in an `SMS_DELIVER` broadcast, with its parts joined.
 *
 * @property address the sender's address as the network gave it (a number or an alphanumeric sender ID).
 * @property sentAtMillis when the service centre received it (the PDU's timestamp).
 * @property subscriptionId the SIM it arrived on, when the broadcast says.
 */
data class IncomingSms(
    val address: String,
    val body: String,
    val sentAtMillis: Long,
    val subscriptionId: Int? = null,
    val serviceCenter: String? = null,
    val protocol: Int? = null,
    val replyPathPresent: Boolean = false,
    val subject: String? = null,
) {
    /** Never prints the text or the sender. */
    override fun toString(): String = "IncomingSms(length=${body.length}, subscriptionId=$subscriptionId)"
}

/** Where a message was stored in Android's message store. */
data class StoredMessage(
    val key: MessageKey,
    val threadId: Long,
)
