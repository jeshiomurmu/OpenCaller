package io.github.jeshiomurmu.opencaller

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.model.AppRole
import io.github.jeshiomurmu.opencaller.core.telephony.RoleManagerHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app's merged manifest meets the default phone app's requirements (feature:incall's InCallService and the
 * ACTION_DIAL filters of MainActivity) and the default SMS app's (feature:messages' SMS_DELIVER and WAP_PUSH_DELIVER
 * receivers and RESPOND_VIA_MESSAGE service, MainActivity's ACTION_SENDTO filter), so both roles can be requested.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q, Build.VERSION_CODES.VANILLA_ICE_CREAM], application = Application::class)
class DefaultAppEligibilityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theAppIsEligibleForTheDialerAndSmsRoles() {
        val roles = RoleManagerHelper(context)
        assertTrue(roles.isAppEligible(AppRole.DIALER))
        assertTrue(roles.isAppEligible(AppRole.CALL_SCREENING))
        assertTrue(roles.isAppEligible(AppRole.SMS))
    }

    @Test
    fun sendToLinksOpenTheMainActivity() {
        listOf("sms:+12015550123", "smsto:+12015550123", "mms:+12015550123", "mmsto:+12015550123").forEach { link ->
            val target =
                Intent(
                    Intent.ACTION_SENDTO,
                    Uri.parse(link),
                ).setPackage(context.packageName).resolveActivity(context.packageManager)
            assertEquals(link, MainActivity::class.java.name, target?.className)
        }
    }

    // The int-flag queries are deprecated from API 33; the test runs on API 29 too.
    @Suppress("DEPRECATION")
    @Test
    fun theSmsComponentsAreProtected() {
        val pm = context.packageManager
        val deliver =
            pm
                .queryBroadcastReceivers(
                    Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION).setPackage(context.packageName),
                    0,
                ).single()
        assertEquals(Manifest.permission.BROADCAST_SMS, deliver.activityInfo.permission)
        val push =
            Intent(
                Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION,
            ).setType("application/vnd.wap.mms-message").setPackage(context.packageName)
        assertEquals(
            Manifest.permission.BROADCAST_WAP_PUSH,
            pm
                .queryBroadcastReceivers(push, 0)
                .single()
                .activityInfo.permission,
        )
        val reply = Intent(TelephonyManager.ACTION_RESPOND_VIA_MESSAGE, Uri.parse("smsto:+12015550123")).setPackage(context.packageName)
        assertEquals(
            Manifest.permission.SEND_RESPOND_VIA_MESSAGE,
            pm
                .queryIntentServices(reply, 0)
                .single()
                .serviceInfo.permission,
        )
    }

    @Test
    fun dialIntentsOpenTheMainActivity() {
        listOf(Intent(Intent.ACTION_DIAL), Intent(Intent.ACTION_DIAL, Uri.parse("tel:+12015550123"))).forEach { dial ->
            val target = dial.setPackage(context.packageName).resolveActivity(context.packageManager)
            assertEquals(MainActivity::class.java.name, target?.className)
        }
    }
}
