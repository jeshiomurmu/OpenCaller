package io.github.jeshiomurmu.opencaller.core.telephony

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.telecom.InCallService
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.model.AppRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The default phone app's requirements (developer.android.com, `InCallService` → "Becoming the Default Phone App"):
 * an activity for ACTION_DIAL with and without tel: data, and an exported InCallService that requires
 * BIND_INCALL_SERVICE and is marked as the in-call UI.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q, Build.VERSION_CODES.VANILLA_ICE_CREAM])
class RoleEligibilityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val packages = shadowOf(context.packageManager)

    private fun eligible() = RoleEligibility.isEligible(context.packageManager, context.packageName, AppRole.DIALER)

    private fun dialActivity(withTel: Boolean = true) {
        val dial = ComponentName(context, "test.DialActivity")
        packages.addActivityIfNotPresent(dial)
        packages.addIntentFilterForActivity(dial, IntentFilter(Intent.ACTION_DIAL).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        if (withTel) {
            packages.addIntentFilterForActivity(
                dial,
                IntentFilter(Intent.ACTION_DIAL).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addDataScheme("tel")
                },
            )
        }
    }

    private fun inCallService(
        permission: String? = Manifest.permission.BIND_INCALL_SERVICE,
        ui: Boolean? = true,
        exported: Boolean = true,
    ) {
        val info =
            ServiceInfo().apply {
                packageName = context.packageName
                name = "test.InCall"
                this.permission = permission
                this.exported = exported
                metaData = ui?.let { Bundle().apply { putBoolean(TelecomManager.METADATA_IN_CALL_SERVICE_UI, it) } }
            }
        packages.addOrUpdateService(info)
        packages.addIntentFilterForService(ComponentName(context, "test.InCall"), IntentFilter(InCallService.SERVICE_INTERFACE))
    }

    @Test
    fun theDocumentedComponentsMakeTheAppEligible() {
        dialActivity()
        inCallService()
        assertTrue(eligible())
    }

    @Test
    fun aDialActivityWithoutTelDataIsNotEnough() {
        dialActivity(withTel = false)
        inCallService()
        assertFalse(eligible())
    }

    @Test
    fun theInCallServiceMustBeTheProtectedInCallUi() {
        dialActivity()
        inCallService(ui = null)
        assertFalse("no IN_CALL_SERVICE_UI meta-data", eligible())
        inCallService(ui = false)
        assertFalse("IN_CALL_SERVICE_UI false", eligible())
        inCallService(permission = null)
        assertFalse("anyone could bind it", eligible())
        inCallService(exported = false)
        assertFalse("Telecom can't bind it", eligible())
    }

    @Test
    fun noDialActivityNoRole() {
        inCallService()
        assertFalse(eligible())
    }
}
