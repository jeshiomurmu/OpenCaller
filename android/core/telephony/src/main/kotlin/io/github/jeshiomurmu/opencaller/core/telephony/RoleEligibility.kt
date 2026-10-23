package io.github.jeshiomurmu.opencaller.core.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telecom.CallScreeningService
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import io.github.jeshiomurmu.opencaller.core.model.AppRole

/**
 * Checks whether OpenCaller's own manifest has the components Android requires before it will grant a role
 * (RoleManager otherwise cancels the request without showing anything). The SMS role lights up automatically once
 * Phase 7 adds its components; until then it shows as "coming later".
 *
 * The default phone app (developer.android.com, `InCallService` → "Becoming the Default Phone App") needs an activity
 * for `ACTION_DIAL` with and without `tel:` data (category `DEFAULT`) and an exported `InCallService` that only
 * Telecom may bind (`BIND_INCALL_SERVICE`) and that replaces the in-call screen (`IN_CALL_SERVICE_UI` = true).
 */
internal object RoleEligibility {
    private const val MMS_MIME_TYPE = "application/vnd.wap.mms-message"

    fun isEligible(
        packageManager: PackageManager,
        packageName: String,
        role: AppRole,
    ): Boolean = requiredComponents(role).all { it.resolvesIn(packageManager, packageName) }

    private fun requiredComponents(role: AppRole): List<Component> =
        when (role) {
            AppRole.CALL_SCREENING -> {
                listOf(Component.Service(Intent(CallScreeningService.SERVICE_INTERFACE)))
            }

            AppRole.DIALER -> {
                listOf(
                    Component.Activity(Intent(Intent.ACTION_DIAL)),
                    Component.Activity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", "", null))),
                    Component.Service(Intent(InCallService.SERVICE_INTERFACE), ServiceInfo::isInCallUi),
                )
            }

            AppRole.SMS -> {
                listOf(
                    Component.Receiver(Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION)),
                    Component.Receiver(Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION).setType(MMS_MIME_TYPE)),
                    Component.Activity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", "", null))),
                    Component.Service(Intent(TelephonyManager.ACTION_RESPOND_VIA_MESSAGE, Uri.fromParts("smsto", "", null))),
                )
            }
        }

    private sealed interface Component {
        val intent: Intent

        fun resolvesIn(
            packageManager: PackageManager,
            packageName: String,
        ): Boolean {
            val own = Intent(intent).setPackage(packageName)
            return when (this) {
                is Activity -> packageManager.activitiesFor(own).isNotEmpty()
                is Service -> packageManager.servicesFor(own).any { info -> info.serviceInfo?.let(accepts) == true }
                is Receiver -> packageManager.receiversFor(own).isNotEmpty()
            }
        }

        data class Activity(
            override val intent: Intent,
        ) : Component

        class Service(
            override val intent: Intent,
            val accepts: (ServiceInfo) -> Boolean = { true },
        ) : Component

        data class Receiver(
            override val intent: Intent,
        ) : Component
    }
}

// The replacement in-call screen: exported, bindable only by Telecom, and marked as the in-call UI.
private fun ServiceInfo.isInCallUi(): Boolean =
    exported &&
        permission == Manifest.permission.BIND_INCALL_SERVICE &&
        metaData?.getBoolean(TelecomManager.METADATA_IN_CALL_SERVICE_UI, false) == true

// The int-flag overloads are deprecated from API 33 in favour of ResolveInfoFlags. Every query is limited to
// OpenCaller's own package, which is always visible, so no <queries> declaration is needed. Activities must be in
// the DEFAULT category, as for any implicit start.
@SuppressLint("QueryPermissionsNeeded")
private fun PackageManager.activitiesFor(intent: Intent): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }

@SuppressLint("QueryPermissionsNeeded")
private fun PackageManager.servicesFor(intent: Intent): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
    } else {
        @Suppress("DEPRECATION")
        queryIntentServices(intent, PackageManager.GET_META_DATA)
    }

@SuppressLint("QueryPermissionsNeeded")
private fun PackageManager.receiversFor(intent: Intent): List<ResolveInfo> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        queryBroadcastReceivers(intent, 0)
    }
