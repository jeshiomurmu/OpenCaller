package io.github.jeshiomurmu.opencaller.feature.messages.receive

import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.IncomingSms

/**
 * Reads an `SMS_DELIVER` broadcast with `Telephony.Sms.Intents.getMessagesFromIntent`: the PDUs of one message
 * (several for a long one), joined in order. The SIM comes from `SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX`, else
 * the broadcast's "subscription" extra (an int on current Android, documented as a long).
 */
internal object IncomingSmsReader {
    /** The message in [intent], or `null` when it holds none. */
    fun read(intent: Intent): IncomingSms? {
        val parts =
            Telephony.Sms.Intents
                .getMessagesFromIntent(intent)
                ?.filterNotNull()
                .orEmpty()
        val first = parts.firstOrNull() ?: return null
        val address = first.displayOriginatingAddress ?: first.originatingAddress
        return address?.takeIf(String::isNotBlank)?.let {
            IncomingSms(
                address = it,
                body = parts.joinToString("") { part -> part.displayMessageBody.orEmpty() },
                sentAtMillis = first.timestampMillis,
                subscriptionId = subscriptionOf(intent),
                serviceCenter = first.serviceCenterAddress,
                protocol = first.protocolIdentifier,
                replyPathPresent = first.isReplyPathPresent,
                subject = first.pseudoSubject?.takeIf(String::isNotBlank),
            )
        }
    }

    /** The SIM a broadcast arrived on, if it says. */
    fun subscriptionOf(intent: Intent): Int? {
        val index = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, INVALID)
        val legacy = if (index == INVALID) intent.getIntExtra(SUBSCRIPTION, INVALID) else index
        val id = if (legacy == INVALID) intent.getLongExtra(SUBSCRIPTION, INVALID.toLong()).toInt() else legacy
        return id.takeIf { it != INVALID && it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
    }

    private const val INVALID = -1
    private const val SUBSCRIPTION = "subscription"
}
