package io.github.jeshiomurmu.opencaller.feature.messages.send

import android.content.Context
import android.os.Build
import android.telephony.SmsManager

/**
 * The `SmsManager` of SIM [subscriptionId] (`createForSubscriptionId`, Android 12+, `getSmsManagerForSubscriptionId`
 * before), or of the default SMS SIM when it is `null`; `null` on a device without SMS.
 */
internal fun Context.smsManagerFor(subscriptionId: Int?): SmsManager? {
    val manager: SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(SmsManager::class.java) ?: return null
        } else {
            @Suppress("DEPRECATION") // Replaced by Context.getSystemService(SmsManager) from Android 12.
            SmsManager.getDefault()
        }
    return when {
        subscriptionId == null -> {
            manager
        }

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            manager.createForSubscriptionId(subscriptionId)
        }

        else -> {
            @Suppress("DEPRECATION") // Replaced by createForSubscriptionId from Android 12.
            SmsManager.getSmsManagerForSubscriptionId(subscriptionId)
        }
    }
}
