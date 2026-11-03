package io.github.jeshiomurmu.opencaller.feature.settings

import android.graphics.Bitmap
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.supporter.StaticEntitlements
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.datastore.inMemory
import io.github.jeshiomurmu.opencaller.core.model.Entitlement
import io.github.jeshiomurmu.opencaller.core.model.EntitlementSource
import io.github.jeshiomurmu.opencaller.core.model.GhostCallSetup
import io.github.jeshiomurmu.opencaller.core.model.PaidTier
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import io.github.jeshiomurmu.opencaller.feature.settings.ghostcall.GhostCallEvent
import io.github.jeshiomurmu.opencaller.feature.settings.ghostcall.GhostCallPhotos
import io.github.jeshiomurmu.opencaller.feature.settings.ghostcall.GhostCallViewModel
import io.github.jeshiomurmu.opencaller.feature.settings.ghostcall.GhostCalls
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** TC-UT-001: the Ghost Call screen keeps a shown-only number and schedules only for supporters. */
@OptIn(ExperimentalCoroutinesApi::class)
class GhostCallViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val settings = UserSettingsDataSource.inMemory()
    private val supporter = StaticEntitlements(Entitlement(true, PaidTier.SUPPORTER, EntitlementSource.BILLING, Long.MAX_VALUE / 2))

    private class RecordingCalls : GhostCalls {
        val scheduled = mutableListOf<Int>()
        var cancelled = 0

        override fun schedule(delaySeconds: Int) {
            scheduled += delaySeconds
        }

        override fun cancel() {
            cancelled++
        }
    }

    private class FakePhotos(
        private val result: AppResult<Unit>,
    ) : GhostCallPhotos {
        override suspend fun save(uri: String): AppResult<Unit> = result

        override suspend fun delete() = Unit

        override suspend fun load(): Bitmap? = null
    }

    private fun TestScope.observe(vm: GhostCallViewModel) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    @Test
    fun setsUpAndSchedulesTheCall() =
        runTest {
            val calls = RecordingCalls()
            val vm = GhostCallViewModel(settings, supporter, calls, FakePhotos(AppResult.Success(Unit)))
            observe(vm)
            vm.onEvent(GhostCallEvent.Name("Mum"))
            vm.onEvent(GhostCallEvent.Number("+44 (7911) 123-456; DROP"))
            vm.onEvent(GhostCallEvent.Delay(60))
            vm.onEvent(GhostCallEvent.Delay(7))
            vm.onEvent(GhostCallEvent.PhotoPicked("content://picked"))
            assertEquals(GhostCallSetup("Mum", "+44 (7911) 123-456", 60, hasPhoto = true), settings.preferences.first().ghostCall)

            vm.onEvent(GhostCallEvent.Schedule)
            assertEquals(listOf(60), calls.scheduled)
            assertEquals(60, vm.uiState.value.scheduledSeconds)
            vm.onEvent(GhostCallEvent.Cancel)
            assertEquals(1, calls.cancelled)
            assertNull(vm.uiState.value.scheduledSeconds)
        }

    @Test
    fun withoutAnEntitlementNothingIsScheduled() =
        runTest {
            val calls = RecordingCalls()
            val vm = GhostCallViewModel(settings, StaticEntitlements(), calls, FakePhotos(AppResult.Failure(AppError.Storage)))
            observe(vm)
            assertFalse(vm.uiState.value.entitled)
            vm.onEvent(GhostCallEvent.Schedule)
            assertTrue(calls.scheduled.isEmpty())
            vm.onEvent(GhostCallEvent.PhotoPicked("content://broken"))
            assertTrue(vm.uiState.value.photoFailed)
            assertFalse(
                settings.preferences
                    .first()
                    .ghostCall.hasPhoto,
            )
        }
}
