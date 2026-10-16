package io.github.jeshiomurmu.opencaller.core.telephony

import android.Manifest
import android.os.Build
import io.github.jeshiomurmu.opencaller.core.model.AppRole
import io.github.jeshiomurmu.opencaller.core.model.RoleState
import io.github.jeshiomurmu.opencaller.core.model.RoleStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.BAKLAVA])
class PermissionPolicyTest {
    private val noRoles = RoleState(RoleStatus.NOT_HELD, RoleStatus.NOT_HELD, RoleStatus.NOT_HELD)
    private val dialer = noRoles.copy(dialer = RoleStatus.HELD)
    private val sms = noRoles.copy(sms = RoleStatus.HELD)

    @Test
    fun callLogAndPhonePermissionsNeedTheDialerRole() {
        PermissionPolicy.DIALER_PERMISSIONS.forEach {
            assertEquals(it, PermissionDecision.RequiresRole(AppRole.DIALER), PermissionPolicy.decide(it, noRoles))
            assertEquals(it, PermissionDecision.RequiresRole(AppRole.DIALER), PermissionPolicy.decide(it, sms))
            assertEquals(it, PermissionDecision.Allowed, PermissionPolicy.decide(it, dialer))
        }
    }

    @Test
    fun smsPermissionsNeedTheSmsRole() {
        PermissionPolicy.SMS_PERMISSIONS.forEach {
            assertEquals(it, PermissionDecision.RequiresRole(AppRole.SMS), PermissionPolicy.decide(it, noRoles))
            assertEquals(it, PermissionDecision.RequiresRole(AppRole.SMS), PermissionPolicy.decide(it, dialer))
            assertEquals(it, PermissionDecision.Allowed, PermissionPolicy.decide(it, sms))
        }
    }

    @Test
    fun onboardingPermissionsNeedNoRole() {
        assertEquals(PermissionDecision.Allowed, PermissionPolicy.decide(Manifest.permission.READ_CONTACTS, noRoles))
        assertEquals(PermissionDecision.Allowed, PermissionPolicy.decide(Manifest.permission.POST_NOTIFICATIONS, noRoles))
    }

    @Test
    fun notificationPermissionExistsFromAndroid13() {
        val permission = Manifest.permission.POST_NOTIFICATIONS
        assertEquals(PermissionDecision.NotNeeded, PermissionPolicy.decide(permission, noRoles, Build.VERSION_CODES.Q))
        assertEquals(PermissionDecision.NotNeeded, PermissionPolicy.decide(permission, noRoles, Build.VERSION_CODES.S_V2))
        assertEquals(PermissionDecision.Allowed, PermissionPolicy.decide(permission, noRoles, Build.VERSION_CODES.TIRAMISU))
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun defaultsToTheDeviceSdk() {
        assertEquals(PermissionDecision.NotNeeded, PermissionPolicy.decide(Manifest.permission.POST_NOTIFICATIONS, noRoles))
    }

    @Test
    fun requestableKeepsOnlyAllowedPermissionsInOrder() {
        val asked =
            listOf(
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALL_LOG,
                Manifest.permission.READ_SMS,
                Manifest.permission.CALL_PHONE,
            )
        assertEquals(listOf(Manifest.permission.READ_CONTACTS), PermissionPolicy.requestable(asked, noRoles))
        assertEquals(
            listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE),
            PermissionPolicy.requestable(asked, dialer),
        )
    }
}
