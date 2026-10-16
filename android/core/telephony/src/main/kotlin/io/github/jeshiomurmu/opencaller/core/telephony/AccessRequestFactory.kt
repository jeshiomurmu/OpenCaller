package io.github.jeshiomurmu.opencaller.core.telephony

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.model.AccessItem
import io.github.jeshiomurmu.opencaller.core.model.AppRole
import io.github.jeshiomurmu.opencaller.core.model.RoleState
import io.github.jeshiomurmu.opencaller.core.model.RoleStatus
import javax.inject.Inject

/** How to ask the user for an [AccessItem]. */
sealed interface AccessRequest {
    /** The system runtime-permission dialog for [permission]. */
    data class RuntimePermission(
        val permission: String,
    ) : AccessRequest

    /** A system screen started for a result: a role dialog or a settings page. */
    data class SystemScreen(
        val intent: Intent,
    ) : AccessRequest
}

/**
 * Builds the request for each [AccessItem], applying [PermissionPolicy]. Callers show a permission
 * explainer first (docs/design.md §7); nothing here starts a request on its own.
 */
class AccessRequestFactory
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val roles: RoleManagerHelper,
    ) {
        /** The request for [item], or `null` when it can't be asked for now (unavailable, not eligible, or policy). */
        fun requestFor(
            item: AccessItem,
            roleState: RoleState,
        ): AccessRequest? =
            when (item) {
                AccessItem.CALL_SCREENING -> {
                    roleRequest(AppRole.CALL_SCREENING, roleState)
                }

                AccessItem.DEFAULT_PHONE_APP -> {
                    roleRequest(AppRole.DIALER, roleState)
                }

                AccessItem.DEFAULT_SMS_APP -> {
                    roleRequest(AppRole.SMS, roleState)
                }

                AccessItem.CONTACTS -> {
                    permissionRequest(Manifest.permission.READ_CONTACTS, roleState)
                }

                AccessItem.NOTIFICATIONS -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionRequest(Manifest.permission.POST_NOTIFICATIONS, roleState)
                    } else {
                        // Before Android 13 there is no permission: the user turns notifications on in settings.
                        settingsFor(item)
                    }
                }

                AccessItem.OVERLAY -> {
                    settingsFor(item)
                }

                AccessItem.CALL_STATE -> {
                    permissionRequest(Manifest.permission.READ_PHONE_STATE, roleState)
                }
            }

        /**
         * The settings page where the user can grant [item] themselves, used once the system stops showing its
         * dialog ("Don't ask again").
         */
        fun settingsFor(item: AccessItem): AccessRequest.SystemScreen =
            AccessRequest.SystemScreen(
                when (item) {
                    AccessItem.CALL_SCREENING, AccessItem.DEFAULT_PHONE_APP, AccessItem.DEFAULT_SMS_APP -> {
                        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                    }

                    AccessItem.CONTACTS, AccessItem.CALL_STATE -> {
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri())
                    }

                    AccessItem.NOTIFICATIONS -> {
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }

                    AccessItem.OVERLAY -> {
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri())
                    }
                },
            )

        private fun roleRequest(
            role: AppRole,
            roleState: RoleState,
        ): AccessRequest? =
            roles
                .takeIf { roleState.statusOf(role) == RoleStatus.NOT_HELD }
                ?.createRequestIntent(role)
                ?.let { AccessRequest.SystemScreen(it) }

        private fun permissionRequest(
            permission: String,
            roleState: RoleState,
        ): AccessRequest? =
            AccessRequest.RuntimePermission(permission).takeIf {
                PermissionPolicy.decide(permission, roleState) == PermissionDecision.Allowed
            }

        private fun packageUri(): Uri = Uri.fromParts("package", context.packageName, null)
    }
