package io.github.jeshiomurmu.opencaller.core.telephony

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.model.AppRole
import io.github.jeshiomurmu.opencaller.core.model.RoleState
import io.github.jeshiomurmu.opencaller.core.model.RoleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowRoleManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q, Build.VERSION_CODES.BAKLAVA])
class RoleManagerHelperTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var roleManager: ShadowRoleManager

    @Before
    fun setUp() {
        roleManager = shadowOf(context.getSystemService(RoleManager::class.java))
        AppRole.entries.forEach { roleManager.addAvailableRole(it.androidName) }
    }

    @Test
    fun onlyCallScreeningIsEligibleInThisBuild() {
        // The module's manifest declares the CallScreeningService; dialer and SMS components arrive in Phases 6–7.
        val helper = RoleManagerHelper(context)
        assertTrue(helper.isAppEligible(AppRole.CALL_SCREENING))
        assertFalse(helper.isAppEligible(AppRole.DIALER))
        assertFalse(helper.isAppEligible(AppRole.SMS))
        assertEquals(
            RoleState(RoleStatus.NOT_HELD, RoleStatus.APP_NOT_ELIGIBLE, RoleStatus.APP_NOT_ELIGIBLE),
            helper.roleState.value,
        )
    }

    @Test
    fun refreshPicksUpGrantedAndRevokedRoles() {
        val helper = RoleManagerHelper(context)
        roleManager.addHeldRole(RoleManager.ROLE_CALL_SCREENING)
        assertEquals(RoleStatus.NOT_HELD, helper.roleState.value.callScreening)

        helper.refresh()
        assertEquals(RoleStatus.HELD, helper.roleState.value.callScreening)
        assertTrue(helper.isRoleHeld(AppRole.CALL_SCREENING))

        roleManager.removeHeldRole(RoleManager.ROLE_CALL_SCREENING)
        helper.refresh()
        assertEquals(RoleStatus.NOT_HELD, helper.roleState.value.callScreening)
    }

    @Test
    fun unavailableRoleHasNoRequest() {
        roleManager.removeAvailableRole(RoleManager.ROLE_CALL_SCREENING)
        val helper = RoleManagerHelper(context)
        assertFalse(helper.isRoleAvailable(AppRole.CALL_SCREENING))
        assertEquals(RoleStatus.UNAVAILABLE, helper.roleState.value.callScreening)
        assertNull(helper.createRequestIntent(AppRole.CALL_SCREENING))
    }

    @Test
    fun availableRoleHasARequestIntent() {
        assertNotNull(RoleManagerHelper(context).createRequestIntent(AppRole.CALL_SCREENING))
    }
}
