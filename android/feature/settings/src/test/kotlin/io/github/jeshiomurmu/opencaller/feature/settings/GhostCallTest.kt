package io.github.jeshiomurmu.opencaller.feature.settings

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import io.github.jeshiomurmu.opencaller.core.data.supporter.StaticEntitlements
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.datastore.inMemory
import io.github.jeshiomurmu.opencaller.core.model.Entitlement
import io.github.jeshiomurmu.opencaller.core.model.EntitlementSource
import io.github.jeshiomurmu.opencaller.core.model.GhostCallSetup
import io.github.jeshiomurmu.opencaller.core.model.PaidTier
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import io.github.jeshiomurmu.opencaller.feature.settings.ghostcall.GhostCallActivity
import io.github.jeshiomurmu.opencaller.feature.settings.ghostcall.GhostCallNotifier
import io.github.jeshiomurmu.opencaller.feature.settings.ghostcall.GhostCallWorker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** TC-UT-001: the Ghost Call rings as a full-screen call notification for our own screen, never as a real call. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class GhostCallTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val supporter = StaticEntitlements(Entitlement(true, PaidTier.SUPPORTER, EntitlementSource.BILLING, Long.MAX_VALUE / 2))
    private val settings = UserSettingsDataSource.inMemory(UserPreferences(ghostCall = GhostCallSetup("Mum", "+44 7911 123456", 10)))

    @Before
    fun grant() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun worker(entitlements: StaticEntitlements): GhostCallWorker =
        TestListenableWorkerBuilder
            .from(context, GhostCallWorker::class.java)
            .setWorkerFactory(
                object : WorkerFactory() {
                    override fun createWorker(
                        appContext: Context,
                        workerClassName: String,
                        workerParameters: WorkerParameters,
                    ): ListenableWorker =
                        GhostCallWorker(appContext, workerParameters, settings, entitlements, GhostCallNotifier(appContext))
                },
            ).build()

    @Test
    fun ringsAFullScreenCallNotificationThatOpensTheFakeCallScreen() {
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker(supporter).doWork() })

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(Notification.CATEGORY_CALL, posted.category)
        assertEquals(GhostCallNotifier.CHANNEL, posted.channelId)
        assertEquals("Mum", posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("+44 7911 123456", posted.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(GhostCallNotifier.RING_MILLIS, posted.timeoutAfter)
        assertNotNull(posted.fullScreenIntent)
        val fullScreen = shadowOf(posted.fullScreenIntent).savedIntent
        assertEquals(GhostCallActivity::class.java.name, fullScreen.component?.className)
        assertEquals(GhostCallActivity.ACTION_RING, fullScreen.action)
        // Every action opens OpenCaller's own screen; nothing dials, nothing goes to Telecom.
        posted.actions.forEach { action ->
            assertEquals(GhostCallActivity::class.java.name, shadowOf(action.actionIntent).savedIntent.component?.className)
        }
        assertNull(shadowOf(context as Application).nextStartedActivity)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel(GhostCallNotifier.CHANNEL).importance)

        GhostCallNotifier(context).clear()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun doesNotRingWithoutAnEntitlement() {
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker(StaticEntitlements()).doWork() })
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
