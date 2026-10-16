package io.github.jeshiomurmu.opencaller.core.telephony

import android.Manifest
import android.os.Build
import io.github.jeshiomurmu.opencaller.core.model.AppRole
import io.github.jeshiomurmu.opencaller.core.model.RoleState

/** Whether a runtime permission may be requested now. */
sealed interface PermissionDecision {
    /** Request it. */
    data object Allowed : PermissionDecision

    /** Google Play policy: request it only while OpenCaller holds [role]. */
    data class RequiresRole(
        val role: AppRole,
    ) : PermissionDecision

    /** This Android version has no such runtime permission (it is implicitly granted or doesn't exist). */
    data object NotNeeded : PermissionDecision
}

/**
 * Google Play's SMS and Call Log policy, enforced before any permission request (docs/architecture.md §5):
 * call log and phone permissions only while OpenCaller is the default phone app, SMS permissions only while
 * it is the default SMS app.
 */
object PermissionPolicy {
    /** Requested only while holding [AppRole.DIALER]. */
    val DIALER_PERMISSIONS: Set<String> =
        setOf(
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.ANSWER_PHONE_CALLS,
        )

    /** Requested only while holding [AppRole.SMS]. */
    val SMS_PERMISSIONS: Set<String> =
        setOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.RECEIVE_WAP_PUSH,
        )

    /** Decides whether [permission] may be requested given the roles OpenCaller holds. */
    fun decide(
        permission: String,
        roles: RoleState,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): PermissionDecision {
        val requiredRole =
            when (permission) {
                in DIALER_PERMISSIONS -> AppRole.DIALER
                in SMS_PERMISSIONS -> AppRole.SMS
                else -> null
            }
        return when {
            permission == Manifest.permission.POST_NOTIFICATIONS && sdkInt < Build.VERSION_CODES.TIRAMISU -> {
                PermissionDecision.NotNeeded
            }

            requiredRole != null && !roles.isHeld(requiredRole) -> {
                PermissionDecision.RequiresRole(requiredRole)
            }

            else -> {
                PermissionDecision.Allowed
            }
        }
    }

    /** The subset of [permissions] that may be requested now, in the same order. */
    fun requestable(
        permissions: List<String>,
        roles: RoleState,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): List<String> = permissions.filter { decide(it, roles, sdkInt) == PermissionDecision.Allowed }
}
