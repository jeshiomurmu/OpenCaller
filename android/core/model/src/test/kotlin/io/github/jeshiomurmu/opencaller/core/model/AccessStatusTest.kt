package io.github.jeshiomurmu.opencaller.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessStatusTest {
    private val nothingGranted =
        AccessStatus(
            roles = RoleState(RoleStatus.NOT_HELD, RoleStatus.APP_NOT_ELIGIBLE, RoleStatus.APP_NOT_ELIGIBLE),
            permissions = PermissionState(PermissionStatus.DENIED, PermissionStatus.DENIED, PermissionStatus.DENIED),
        )

    @Test
    fun degradedOnlyWhileCallScreeningCanBeGrantedButIsNot() {
        assertTrue(nothingGranted.isDegraded)
        assertFalse(nothingGranted.withScreening(RoleStatus.HELD).isDegraded)
        // A device without the role can't do better, so there is nothing to fix.
        assertFalse(nothingGranted.withScreening(RoleStatus.UNAVAILABLE).isDegraded)
    }

    @Test
    fun itemStatusesFollowRolesAndPermissions() {
        assertEquals(AccessItemStatus.NOT_GRANTED, nothingGranted.statusOf(AccessItem.CALL_SCREENING))
        assertEquals(AccessItemStatus.COMING_LATER, nothingGranted.statusOf(AccessItem.DEFAULT_PHONE_APP))
        assertEquals(AccessItemStatus.COMING_LATER, nothingGranted.statusOf(AccessItem.DEFAULT_SMS_APP))
        assertEquals(AccessItemStatus.NOT_GRANTED, nothingGranted.statusOf(AccessItem.CONTACTS))
        val oldAndroid = nothingGranted.copy(permissions = nothingGranted.permissions.copy(notifications = PermissionStatus.NOT_REQUIRED))
        assertEquals(AccessItemStatus.NOT_APPLICABLE, oldAndroid.statusOf(AccessItem.NOTIFICATIONS))
        assertTrue(oldAndroid.isSettled(AccessItem.NOTIFICATIONS))
        assertFalse(oldAndroid.isSettled(AccessItem.OVERLAY))
        // Call state needs no permission before Android 12 (the default), so it is settled there.
        assertEquals(AccessItemStatus.NOT_APPLICABLE, nothingGranted.statusOf(AccessItem.CALL_STATE))
        val android12 = nothingGranted.copy(permissions = nothingGranted.permissions.copy(callState = PermissionStatus.DENIED))
        assertEquals(AccessItemStatus.NOT_GRANTED, android12.statusOf(AccessItem.CALL_STATE))
    }

    @Test
    fun callerIdFallsBackToNotificationWithoutOverlay() {
        // TC-ONB-007: overlay denied -> heads-up notification.
        assertEquals(CallerIdSurface.NOTIFICATION, nothingGranted.callerIdSurface(overlayEnabled = true))
        val overlay = nothingGranted.copy(permissions = nothingGranted.permissions.copy(overlay = PermissionStatus.GRANTED))
        assertEquals(CallerIdSurface.OVERLAY, overlay.callerIdSurface(overlayEnabled = true))
        assertEquals(CallerIdSurface.NOTIFICATION, overlay.callerIdSurface(overlayEnabled = false))
        val dialer = overlay.copy(roles = overlay.roles.copy(dialer = RoleStatus.HELD))
        assertEquals(CallerIdSurface.IN_CALL_SCREEN, dialer.callerIdSurface(overlayEnabled = true))
    }

    @Test
    fun afterCallFallsBackToHistoryWithoutNotifications() {
        // TC-ONB-006: notifications denied -> in-app history.
        assertEquals(AfterCallSurface.IN_APP_HISTORY, nothingGranted.afterCallSurface)
        val allowed = nothingGranted.copy(permissions = nothingGranted.permissions.copy(notifications = PermissionStatus.GRANTED))
        assertEquals(AfterCallSurface.NOTIFICATION, allowed.afterCallSurface)
        val oldAndroid = nothingGranted.copy(permissions = nothingGranted.permissions.copy(notifications = PermissionStatus.NOT_REQUIRED))
        assertEquals(AfterCallSurface.NOTIFICATION, oldAndroid.afterCallSurface)
    }

    @Test
    fun roleStateLooksUpEachRole() {
        val roles = RoleState(RoleStatus.HELD, RoleStatus.NOT_HELD, RoleStatus.UNAVAILABLE)
        assertTrue(roles.isHeld(AppRole.CALL_SCREENING))
        assertEquals(RoleStatus.NOT_HELD, roles.statusOf(AppRole.DIALER))
        assertEquals(RoleStatus.UNAVAILABLE, roles.statusOf(AppRole.SMS))
    }

    private fun AccessStatus.withScreening(status: RoleStatus) = copy(roles = roles.copy(callScreening = status))
}
