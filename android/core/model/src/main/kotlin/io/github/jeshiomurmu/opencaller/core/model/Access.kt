package io.github.jeshiomurmu.opencaller.core.model

/** A default-app role OpenCaller can ask the user for (docs/architecture.md §5). */
enum class AppRole {
    /** Caller ID & spam app: screen incoming calls with any dialer. */
    CALL_SCREENING,

    /** Default phone app: dialer, in-call screen, call log. */
    DIALER,

    /** Default SMS app. */
    SMS,
}

/** Whether OpenCaller holds a role. */
enum class RoleStatus {
    /** The device does not offer this role (for example, no telephony). */
    UNAVAILABLE,

    /** This version of OpenCaller does not ship the parts Android requires for the role yet. */
    APP_NOT_ELIGIBLE,

    /** Available, but another app (or none) holds it. */
    NOT_HELD,

    /** OpenCaller holds the role. */
    HELD,
}

/** Status of every [AppRole]. */
data class RoleState(
    val callScreening: RoleStatus,
    val dialer: RoleStatus,
    val sms: RoleStatus,
) {
    /** Status of [role]. */
    fun statusOf(role: AppRole): RoleStatus =
        when (role) {
            AppRole.CALL_SCREENING -> callScreening
            AppRole.DIALER -> dialer
            AppRole.SMS -> sms
        }

    /** True when OpenCaller holds [role]. */
    fun isHeld(role: AppRole): Boolean = statusOf(role) == RoleStatus.HELD
}

/** Whether a runtime or special permission is granted. */
enum class PermissionStatus {
    /** Granted. */
    GRANTED,

    /** Not granted (never asked, denied, or revoked). */
    DENIED,

    /** Not needed on this Android version (for example, notifications before Android 13). */
    NOT_REQUIRED,
}

/** Status of the permissions OpenCaller asks for during onboarding. */
data class PermissionState(
    val contacts: PermissionStatus,
    val notifications: PermissionStatus,
    val overlay: PermissionStatus,
)

/** Something the user can grant from onboarding or Settings → Permissions & roles. */
enum class AccessItem {
    /** [AppRole.CALL_SCREENING]. */
    CALL_SCREENING,

    /** Read contacts, to show your own names for your contacts. */
    CONTACTS,

    /** Post notifications (Android 13+). */
    NOTIFICATIONS,

    /** [AppRole.DIALER]. */
    DEFAULT_PHONE_APP,

    /** [AppRole.SMS]. */
    DEFAULT_SMS_APP,

    /** Draw the caller ID card over other apps. */
    OVERLAY,
}

/** What the user sees for an [AccessItem]. */
enum class AccessItemStatus {
    /** Granted or held. */
    GRANTED,

    /** Can be granted but is not. */
    NOT_GRANTED,

    /** Not needed or not offered on this device. */
    NOT_APPLICABLE,

    /** OpenCaller can't take this role yet (a later version adds it). */
    COMING_LATER,
}

/** How the caller ID card is shown for an incoming call. */
enum class CallerIdSurface {
    /** In OpenCaller's own in-call screen (default phone app). */
    IN_CALL_SCREEN,

    /** As a card drawn over the system's call screen. */
    OVERLAY,

    /** As a heads-up notification. */
    NOTIFICATION,
}

/** Where the after-call summary goes. */
enum class AfterCallSurface {
    /** A notification. */
    NOTIFICATION,

    /** Only the in-app call history (notifications are off). */
    IN_APP_HISTORY,
}

/** Roles and permissions at one point in time. */
data class AccessStatus(
    val roles: RoleState,
    val permissions: PermissionState,
) {
    /**
     * True when OpenCaller can't screen calls because the user hasn't given it the call screening role
     * (shown as the app-wide degraded-mode banner).
     */
    val isDegraded: Boolean get() = roles.callScreening == RoleStatus.NOT_HELD

    /** Status of [item] as shown in onboarding and on the status screen. */
    fun statusOf(item: AccessItem): AccessItemStatus =
        when (item) {
            AccessItem.CALL_SCREENING -> roles.callScreening.toItemStatus()
            AccessItem.DEFAULT_PHONE_APP -> roles.dialer.toItemStatus()
            AccessItem.DEFAULT_SMS_APP -> roles.sms.toItemStatus()
            AccessItem.CONTACTS -> permissions.contacts.toItemStatus()
            AccessItem.NOTIFICATIONS -> permissions.notifications.toItemStatus()
            AccessItem.OVERLAY -> permissions.overlay.toItemStatus()
        }

    /** True when [item] is granted, or doesn't apply, so there is nothing left to ask for. */
    fun isSettled(item: AccessItem): Boolean =
        statusOf(item) in setOf(AccessItemStatus.GRANTED, AccessItemStatus.NOT_APPLICABLE, AccessItemStatus.COMING_LATER)

    /** Where caller ID appears for an incoming call. [overlayEnabled] is the user's setting. */
    fun callerIdSurface(overlayEnabled: Boolean): CallerIdSurface =
        when {
            roles.isHeld(AppRole.DIALER) -> CallerIdSurface.IN_CALL_SCREEN
            overlayEnabled && permissions.overlay == PermissionStatus.GRANTED -> CallerIdSurface.OVERLAY
            else -> CallerIdSurface.NOTIFICATION
        }

    /** Where after-call summaries go: notifications when allowed, otherwise only the in-app history. */
    val afterCallSurface: AfterCallSurface
        get() =
            if (permissions.notifications == PermissionStatus.DENIED) {
                AfterCallSurface.IN_APP_HISTORY
            } else {
                AfterCallSurface.NOTIFICATION
            }
}

private fun RoleStatus.toItemStatus(): AccessItemStatus =
    when (this) {
        RoleStatus.HELD -> AccessItemStatus.GRANTED
        RoleStatus.NOT_HELD -> AccessItemStatus.NOT_GRANTED
        RoleStatus.APP_NOT_ELIGIBLE -> AccessItemStatus.COMING_LATER
        RoleStatus.UNAVAILABLE -> AccessItemStatus.NOT_APPLICABLE
    }

private fun PermissionStatus.toItemStatus(): AccessItemStatus =
    when (this) {
        PermissionStatus.GRANTED -> AccessItemStatus.GRANTED
        PermissionStatus.DENIED -> AccessItemStatus.NOT_GRANTED
        PermissionStatus.NOT_REQUIRED -> AccessItemStatus.NOT_APPLICABLE
    }
