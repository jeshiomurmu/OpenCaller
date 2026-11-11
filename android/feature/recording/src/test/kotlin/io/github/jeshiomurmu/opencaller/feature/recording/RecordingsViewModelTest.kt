package io.github.jeshiomurmu.opencaller.feature.recording

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.SavedRecording
import io.github.jeshiomurmu.opencaller.core.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingsViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val recordings = FakeRecordings()
    private val player = FakePlayer()
    private val vm by lazy { RecordingsViewModel(recordings, player) }
    private val saved = SavedRecording("r-1", OTHER, "/files/recordings/r-1.ogg", 61_000, "Hello.", 0)

    @Test
    fun recordingsArePlayedDeletedAndTranscriptsSwitched() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            recordings.saved.value = listOf(saved)
            vm.onEvent(RecordingsEvent.Refresh)
            assertEquals(listOf(saved), vm.uiState.value.recordings)
            assertEquals(false, vm.uiState.value.transcripts)

            vm.onEvent(RecordingsEvent.TogglePlay(saved))
            assertEquals("r-1", vm.uiState.value.playingId)
            assertEquals(listOf("/files/recordings/r-1.ogg"), player.played)
            vm.onEvent(RecordingsEvent.TogglePlay(saved))
            assertNull(vm.uiState.value.playingId)

            vm.onEvent(RecordingsEvent.SetTranscripts(true))
            assertEquals(true, vm.uiState.value.transcripts)

            vm.onEvent(RecordingsEvent.Delete("r-1"))
            assertEquals(listOf("r-1"), recordings.deleted)
            assertEquals(emptyList<SavedRecording>(), vm.uiState.value.recordings)
        }

    @Test
    fun failuresShowAMessage() =
        runTest {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            recordings.syncResult = AppResult.Failure(AppError.Network)
            vm.onEvent(RecordingsEvent.Refresh)
            assertEquals(R.string.recordings_network_error, vm.uiState.value.message)
            vm.onEvent(RecordingsEvent.MessageShown)
            assertNull(vm.uiState.value.message)

            player.works = false
            vm.onEvent(RecordingsEvent.TogglePlay(saved))
            assertEquals(R.string.recordings_cannot_play, vm.uiState.value.message)
        }
}

private class FakePlayer : RecordingPlayer {
    override val playing = MutableStateFlow<String?>(null)
    val played = mutableListOf<String>()
    var works = true

    override fun play(
        id: String,
        path: String,
    ): Boolean {
        if (!works) return false
        played += path
        playing.value = id
        return true
    }

    override fun stop() {
        playing.value = null
    }
}
