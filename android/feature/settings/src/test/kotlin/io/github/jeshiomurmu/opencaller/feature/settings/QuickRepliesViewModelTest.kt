package io.github.jeshiomurmu.opencaller.feature.settings

import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.datastore.inMemory
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuickRepliesViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val settings = UserSettingsDataSource.inMemory()
    private val vm by lazy { QuickRepliesViewModel(settings) }

    private fun TestScope.observe() {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    private suspend fun stored() = settings.preferences.first().quickReplies

    private fun write(text: String) {
        vm.onEvent(QuickRepliesEvent.Draft(text))
        vm.onEvent(QuickRepliesEvent.Save)
    }

    @Test
    fun repliesAreAddedEditedMovedAndDeleted() =
        runTest {
            observe()
            write("  In a meeting  ")
            write("Driving, call you later")
            assertEquals(listOf("In a meeting", "Driving, call you later"), stored())
            assertEquals("", vm.uiState.value.draft)

            vm.onEvent(QuickRepliesEvent.Edit(0))
            assertEquals("In a meeting", vm.uiState.value.draft)
            write("In a meeting, text me")
            assertEquals(listOf("In a meeting, text me", "Driving, call you later"), stored())
            assertNull(vm.uiState.value.editing)

            vm.onEvent(QuickRepliesEvent.MoveUp(1))
            assertEquals(listOf("Driving, call you later", "In a meeting, text me"), stored())
            vm.onEvent(QuickRepliesEvent.MoveUp(0))
            assertEquals(listOf("Driving, call you later", "In a meeting, text me"), stored())

            vm.onEvent(QuickRepliesEvent.Delete(0))
            assertEquals(listOf("In a meeting, text me"), vm.uiState.value.replies)
        }

    @Test
    fun emptyDuplicateAndTooManyRepliesAreRefused() =
        runTest {
            observe()
            write("   ")
            assertEquals(QuickReplyProblem.EMPTY, vm.uiState.value.problem)
            write("Busy")
            write("Busy")
            assertEquals(QuickReplyProblem.DUPLICATE, vm.uiState.value.problem)
            assertEquals(listOf("Busy"), stored())

            (2..UserPreferences.MAX_QUICK_REPLIES).forEach { write("Reply $it") }
            assertEquals(UserPreferences.MAX_QUICK_REPLIES, stored().size)
            assertFalse(vm.uiState.value.canAdd)
            write("One more")
            assertEquals(QuickReplyProblem.FULL, vm.uiState.value.problem)
            assertEquals(UserPreferences.MAX_QUICK_REPLIES, stored().size)
        }

    @Test
    fun aReplyIsCutToOneTextMessage() =
        runTest {
            observe()
            vm.onEvent(QuickRepliesEvent.Draft("x".repeat(UserPreferences.MAX_QUICK_REPLY_LENGTH + 20)))
            assertEquals(UserPreferences.MAX_QUICK_REPLY_LENGTH, vm.uiState.value.draft.length)
        }

    @Test
    fun editingStopsWhenTheListIsReordered() =
        runTest {
            observe()
            write("A")
            write("B")
            vm.onEvent(QuickRepliesEvent.Edit(1))
            vm.onEvent(QuickRepliesEvent.Delete(0))
            assertNull(vm.uiState.value.editing)
            write("C")
            assertEquals(listOf("B", "C"), stored())
        }
}
