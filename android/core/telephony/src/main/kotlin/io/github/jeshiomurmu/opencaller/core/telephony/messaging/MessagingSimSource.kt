package io.github.jeshiomurmu.opencaller.core.telephony.messaging

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.model.MessagingSim
import io.github.jeshiomurmu.opencaller.core.model.MessagingSims
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** The SIMs messages can be sent with, and the user's own numbers (to leave the user out of group conversations). */
interface MessagingSimSource {
    /** The active SIMs and Android's default SMS SIM; empty without READ_PHONE_STATE. */
    fun sims(): MessagingSims

    /** The numbers of the active SIMs that Android knows (often none: many SIMs don't store their number). */
    fun ownNumbers(): Set<String>
}

/**
 * [MessagingSimSource] from `SubscriptionManager.getActiveSubscriptionInfoList` (READ_PHONE_STATE) and
 * `SmsManager.getDefaultSmsSubscriptionId`; own numbers from `SubscriptionManager.getPhoneNumber` on Android 13+ and
 * `SubscriptionInfo.getNumber` before (READ_PHONE_NUMBERS). The default SMS app role grants both permission groups.
 */
@Singleton
class DeviceMessagingSims
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : MessagingSimSource {
        private val subscriptions: SubscriptionManager? get() = context.getSystemService(SubscriptionManager::class.java)

        override fun sims(): MessagingSims {
            val active = active()
            // An empty label: the screen names the SIM by its slot.
            val options =
                active.map {
                    MessagingSim(
                        it.subscriptionId,
                        it.displayName
                            ?.toString()
                            .orEmpty()
                            .trim(),
                        it.simSlotIndex,
                    )
                }
            val default = SmsManager.getDefaultSmsSubscriptionId().takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
            return MessagingSims(options, default?.takeIf { id -> options.any { it.subscriptionId == id } })
        }

        // Checked just above; a revoked permission is caught as SecurityException.
        @SuppressLint("MissingPermission")
        override fun ownNumbers(): Set<String> {
            val manager = subscriptions?.takeIf { granted(Manifest.permission.READ_PHONE_NUMBERS) } ?: return emptySet()
            return try {
                active().mapNotNull { info -> numberOf(manager, info)?.takeIf(String::isNotBlank) }.toSet()
            } catch (e: SecurityException) {
                Timber.w(e, "Own numbers not readable")
                emptySet()
            }
        }

        @SuppressLint("MissingPermission")
        private fun numberOf(
            manager: SubscriptionManager,
            info: SubscriptionInfo,
        ): String? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.getPhoneNumber(info.subscriptionId)
            } else {
                @Suppress("DEPRECATION") // getPhoneNumber replaces it from Android 13.
                info.number
            }

        // Checked just above; a revoked permission is caught as SecurityException.
        @SuppressLint("MissingPermission")
        private fun active(): List<SubscriptionInfo> {
            if (!granted(Manifest.permission.READ_PHONE_STATE)) return emptyList()
            return try {
                subscriptions?.activeSubscriptionInfoList.orEmpty()
            } catch (e: SecurityException) {
                Timber.w(e, "SIMs not readable")
                emptyList()
            } catch (e: UnsupportedOperationException) {
                Timber.w(e, "No telephony subscriptions on this device")
                emptyList()
            }
        }

        private fun granted(permission: String): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
