package io.github.jeshiomurmu.opencaller

import android.app.Application
import android.content.Context
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * No component of the merged manifest (the libraries' included) is reachable by other apps unless Android itself
 * guards it with a system permission, or it is on the short, reviewed list below (docs/security.md,
 * MASVS-PLATFORM-1). A new exported component fails this test until it is reviewed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM], application = Application::class)
class ExportedComponentsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // The int-flag query is deprecated from API 33; the flags still work.
    @Suppress("DEPRECATION")
    private fun exported(): List<Pair<ComponentInfo, String?>> {
        val flags =
            PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        return buildList {
            info.activities.orEmpty().forEach { add(it to it.permission) }
            info.services.orEmpty().forEach { add(it to it.permission) }
            info.receivers.orEmpty().forEach { add(it to it.permission) }
            info.providers.orEmpty().forEach { add(it to (it.writePermission ?: it.readPermission)) }
        }.filter { (component, _) -> component.exported && !component.name.startsWith(TEST_ONLY_PREFIX) }
    }

    @Test
    fun everyExportedComponentIsGuardedOrReviewed() {
        val unexpected =
            exported().filterNot { (component, permission) ->
                permission in SYSTEM_GUARDS || component.name in REVIEWED_UNGUARDED
            }
        assertTrue(
            "Exported without a system guard: " + unexpected.joinToString { (c, p) -> "${c.name} (permission=$p)" },
            unexpected.isEmpty(),
        )
    }

    @Test
    fun theQaReceiverExistsOnlyInDebugBuildsAndOnlyForTheShell() {
        val qa = exported().filter { (component, _) -> component.name.endsWith(".qa.QaCommandReceiver") }
        if (BuildConfig.DEBUG) {
            assertEquals(listOf("android.permission.DUMP"), qa.map { it.second })
        } else {
            assertTrue("QaCommandReceiver in a release build", qa.isEmpty())
        }
    }

    private companion object {
        // The unit-test manifest also merges the test dependencies' manifests: androidx.test:core declares exported
        // activities (InstrumentationActivityInvoker's bootstrap and empty activities) that never ship in the app.
        const val TEST_ONLY_PREFIX = "androidx.test."

        // Permissions only the system (or Google Play services, for FCM) can hold.
        val SYSTEM_GUARDS =
            setOf(
                "android.permission.BIND_SCREENING_SERVICE",
                "android.permission.BIND_INCALL_SERVICE",
                "android.permission.BROADCAST_SMS",
                "android.permission.BROADCAST_WAP_PUSH",
                "android.permission.SEND_RESPOND_VIA_MESSAGE",
                "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
                "android.permission.BIND_JOB_SERVICE",
                "android.permission.BIND_TELECOM_CONNECTION_SERVICE",
                // adb shell and the system only: WorkManager's diagnostics, profileinstaller, the debug QA receiver.
                "android.permission.DUMP",
                "com.google.android.c2dm.permission.SEND",
            )

        // Reachable by any app on purpose, each reviewed in docs/security.md.
        val REVIEWED_UNGUARDED =
            setOf(
                // The launcher entry; DIAL and SENDTO links. Extras are validated (MainViewModelTest).
                "io.github.jeshiomurmu.opencaller.MainActivity",
                // foss: the UnifiedPush distributor (any app the user picks) delivers here. Messages need the
                // registration token, only decrypted Web Push bodies are used, and they carry only a random id
                // fetched from our server with the user's session (ADR 0007).
                "org.unifiedpush.android.connector.internal.MessagingReceiverImpl",
                "org.unifiedpush.android.connector.internal.RaiseToForegroundService",
            )
    }
}
