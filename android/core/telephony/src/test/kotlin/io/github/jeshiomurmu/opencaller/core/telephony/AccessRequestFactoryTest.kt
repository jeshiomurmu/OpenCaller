package io.github.jeshiomurmu.opencaller.core.telephony

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.model.AccessItem
import io.github.jeshiomurmu.opencaller.core.model.AppRole
import io.github.jeshiomurmu.opencaller.core.model.RoleState
import io.github.jeshiomurmu.opencaller.core.model.RoleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowRoleManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.BAKLAVA])
class AccessRequestFactoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fresh = RoleState(RoleStatus.NOT_HELD, RoleStatus.APP_NOT_ELIGIBLE, RoleStatus.APP_NOT_ELIGIBLE)
    private val user = Process.myUserHandle()
    private lateinit var factory: AccessRequestFactory

    @Before
    fun setUp() {
        // Robolectric treats a role as available while it has any holder; "" stands for "some other app".
        AppRole.entries.forEach { ShadowRoleManager.addRoleHolder(it.androidName, "", user) }
        factory = AccessRequestFactory(context, RoleManagerHelper(context))
    }

    @Test
    fun callScreeningUsesTheRoleDialogUntilHeld() {
        assertTrue(factory.requestFor(AccessItem.CALL_SCREENING, fresh) is AccessRequest.SystemScreen)
        assertNull(factory.requestFor(AccessItem.CALL_SCREENING, fresh.copy(callScreening = RoleStatus.HELD)))
    }

    @Test
    fun rolesTheAppCantTakeYetHaveNoRequest() {
        assertNull(factory.requestFor(AccessItem.DEFAULT_PHONE_APP, fresh))
        assertNull(factory.requestFor(AccessItem.DEFAULT_SMS_APP, fresh))
    }

    @Test
    fun runtimePermissionsUseTheSystemDialog() {
        assertEquals(
            AccessRequest.RuntimePermission(Manifest.permission.READ_CONTACTS),
            factory.requestFor(AccessItem.CONTACTS, fresh),
        )
        assertEquals(
            AccessRequest.RuntimePermission(Manifest.permission.POST_NOTIFICATIONS),
            factory.requestFor(AccessItem.NOTIFICATIONS, fresh),
        )
    }

    @Test
    @Config(sdk = [Build.VERSION_CODES.Q])
    fun notificationsBeforeAndroid13OpenTheAppsNotificationSettings() {
        val request = factory.requestFor(AccessItem.NOTIFICATIONS, fresh) as AccessRequest.SystemScreen
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, request.intent.action)
        assertEquals(context.packageName, request.intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun overlayOpensTheOverlaySettings() {
        val request = factory.requestFor(AccessItem.OVERLAY, fresh) as AccessRequest.SystemScreen
        assertEquals(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, request.intent.action)
    }

    @Test
    fun settingsFallbacksPointAtTheRightPages() {
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, factory.settingsFor(AccessItem.CALL_SCREENING).intent.action)
        val contacts = factory.settingsFor(AccessItem.CONTACTS).intent
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, contacts.action)
        assertEquals("package:${context.packageName}", contacts.dataString)
    }
}
