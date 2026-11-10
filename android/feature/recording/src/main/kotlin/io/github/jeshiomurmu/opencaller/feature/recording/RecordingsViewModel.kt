package io.github.jeshiomurmu.opencaller.feature.recording

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.recording.CallRecordings
import io.github.jeshiomurmu.opencaller.core.model.SavedRecording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Recordings screen. [transcripts] is `null` until the server answered (or when it can't be reached). */
data class RecordingsUiState(
    val recordings: List<SavedRecording> = emptyList(),
    val playingId: String? = null,
    val transcripts: Boolean? = null,
    val busy: Boolean = false,
    @param:StringRes val message: Int? = null,
)

/** What the user does on the Recordings screen. */
sealed interface RecordingsEvent {
    /** Fetch recordings still on the server and the transcript setting. */
    data object Refresh : RecordingsEvent

    /** Play or stop [recording]. */
    data class TogglePlay(
        val recording: SavedRecording,
    ) : RecordingsEvent

    /** Delete recording [id] (after the screen's confirmation). */
    data class Delete(
        val id: String,
    ) : RecordingsEvent

    /** Turn transcripts for new recordings on or off. */
    data class SetTranscripts(
        val enabled: Boolean,
    ) : RecordingsEvent

    /** The message was shown. */
    data object MessageShown : RecordingsEvent
}

/** Saved recordings, playback, delete and the transcript switch. */
@HiltViewModel
class RecordingsViewModel
    @Inject
    constructor(
        private val recordings: CallRecordings,
        private val player: RecordingPlayer,
    ) : ViewModel() {
        private val local = MutableStateFlow(RecordingsUiState())

        /** Screen state. */
        val uiState: StateFlow<RecordingsUiState> =
            combine(local, recordings.saved, player.playing) { state, saved, playing ->
                state.copy(recordings = saved, playingId = playing)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RecordingsUiState())

        /** Handles [event]. */
        fun onEvent(event: RecordingsEvent) {
            when (event) {
                RecordingsEvent.Refresh -> refresh()
                is RecordingsEvent.TogglePlay -> togglePlay(event.recording)
                is RecordingsEvent.Delete -> delete(event.id)
                is RecordingsEvent.SetTranscripts -> setTranscripts(event.enabled)
                RecordingsEvent.MessageShown -> local.update { it.copy(message = null) }
            }
        }

        private fun refresh() =
            busy {
                val synced = recordings.syncAll()
                val transcripts = (recordings.transcripts() as? AppResult.Success)?.value
                local.update { it.copy(transcripts = transcripts, message = (synced as? AppResult.Failure)?.error?.let(::messageOf)) }
            }

        private fun togglePlay(recording: SavedRecording) {
            if (player.playing.value == recording.id) {
                player.stop()
            } else if (!player.play(recording.id, recording.filePath)) {
                local.update { it.copy(message = R.string.recordings_cannot_play) }
            }
        }

        private fun delete(id: String) =
            busy {
                if (player.playing.value == id) player.stop()
                val result = recordings.delete(id)
                if (result is AppResult.Failure) local.update { it.copy(message = messageOf(result.error)) }
            }

        private fun setTranscripts(enabled: Boolean) =
            busy {
                when (val result = recordings.setTranscripts(enabled)) {
                    is AppResult.Success -> local.update { it.copy(transcripts = result.value) }
                    is AppResult.Failure -> local.update { it.copy(message = messageOf(result.error)) }
                }
            }

        private fun busy(block: suspend () -> Unit) {
            viewModelScope.launch {
                local.update { it.copy(busy = true) }
                try {
                    block()
                } finally {
                    local.update { it.copy(busy = false) }
                }
            }
        }

        override fun onCleared() {
            player.stop()
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L

            fun messageOf(error: AppError): Int =
                when (error) {
                    AppError.Auth, AppError.ServerNotConfigured -> R.string.recordings_signed_out
                    AppError.Storage -> R.string.recordings_storage_error
                    else -> R.string.recordings_network_error
                }
        }
    }
