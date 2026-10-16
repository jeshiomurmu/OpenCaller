package io.github.jeshiomurmu.opencaller.core.telephony

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.model.PermissionState
import io.github.jeshiomurmu.opencaller.core.model.PermissionStatus
import javax.inject.Inject

/** Reads the current state of the permissions OpenCaller asks for during onboarding. */
class PermissionStatusReader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** A fresh snapshot. */
        fun read(): PermissionState =
            PermissionState(
                contacts = runtime(Manifest.permission.READ_CONTACTS),
                notifications = notifications(),
                overlay = if (Settings.canDrawOverlays(context)) PermissionStatus.GRANTED else PermissionStatus.DENIED,
            )

        private fun runtime(permission: String): PermissionStatus =
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                PermissionStatus.GRANTED
            } else {
                PermissionStatus.DENIED
            }

        // Before Android 13 notifications need no runtime permission, but the user can still turn them off.
        private fun notifications(): PermissionStatus {
            val enabled = context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                    if (enabled && runtime(Manifest.permission.POST_NOTIFICATIONS) == PermissionStatus.GRANTED) {
                        PermissionStatus.GRANTED
                    } else {
                        PermissionStatus.DENIED
                    }
                }

                enabled -> {
                    PermissionStatus.NOT_REQUIRED
                }

                else -> {
                    PermissionStatus.DENIED
                }
            }
        }
    }
