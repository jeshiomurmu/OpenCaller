package io.github.jeshiomurmu.opencaller

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.model.AppRole
import io.github.jeshiomurmu.opencaller.core.telephony.RoleManagerHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app's merged manifest meets the default phone app's requirements (feature:incall's InCallService and the
 * ACTION_DIAL filters of MainActivity), so the dialer role can be requested; the SMS role can't yet (Phase 7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q, Build.VERSION_CODES.VANILLA_ICE_CREAM], application = Application::class)
class DialerEligibilityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theAppIsEligibleForTheDialerRole() {
        val roles = RoleManagerHelper(context)
        assertTrue(roles.isAppEligible(AppRole.DIALER))
        assertTrue(roles.isAppEligible(AppRole.CALL_SCREENING))
        assertFalse(roles.isAppEligible(AppRole.SMS))
    }

    @Test
    fun dialIntentsOpenTheMainActivity() {
        listOf(Intent(Intent.ACTION_DIAL), Intent(Intent.ACTION_DIAL, Uri.parse("tel:+12015550123"))).forEach { dial ->
            val target = dial.setPackage(context.packageName).resolveActivity(context.packageManager)
            assertEquals(MainActivity::class.java.name, target?.className)
        }
    }
}
