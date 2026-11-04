package io.github.jeshiomurmu.opencaller.feature.social

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.data.account.AccountStatus
import io.github.jeshiomurmu.opencaller.core.data.voip.AppCallRepository
import io.github.jeshiomurmu.opencaller.core.data.voip.FlashRepository
import io.github.jeshiomurmu.opencaller.core.data.voip.VideoCallerIdRepository
import io.github.jeshiomurmu.opencaller.core.model.FlashMessage
import io.github.jeshiomurmu.opencaller.core.model.MyVideoCallerId
import io.github.jeshiomurmu.opencaller.core.model.VideoJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Settings → App calls, Flash and video caller ID.
 *
 * @property reachable whether app calls and Flash messages reach the user (`null` until the server answered).
 * @property video the user's clip and its upload state (`null` until loaded).
 * @property error the last failure's text.
 */
data class VoipSettingsUiState(
    val signedIn: Boolean = false,
    val reachable: Boolean? = null,
    val clipsOnMobileData: Boolean = false,
    val video: MyVideoCallerId? = null,
    val busy: Boolean = false,
    @param:StringRes val error: Int? = null,
    val recent: List<FlashMessage> = emptyList(),
)

/** What the settings screen can do. */
sealed interface VoipSettingsEvent {
    /** Turn reachability on or off. */
    data class SetReachable(
        val reachable: Boolean,
    ) : VoipSettingsEvent

    /** Allow or forbid clip downloads on mobile data. */
    data class SetClipsOnMobileData(
        val allowed: Boolean,
    ) : VoipSettingsEvent

    /** Upload a recorded clip. */
    data class Upload(
        val path: String,
    ) : VoipSettingsEvent

    /** Delete the user's clip. */
    data object DeleteVideo : VoipSettingsEvent

    /** Read the server's state again. */
    data object Refresh : VoipSettingsEvent
}

/** Reachability, clip downloads, the user's own video caller ID and recent Flash messages. */
@HiltViewModel
class VoipSettingsViewModel
    @Inject
    constructor(
        account: AccountStatus,
        private val calls: AppCallRepository,
        private val videos: VideoCallerIdRepository,
        flash: FlashRepository,
    ) : ViewModel() {
        private val local = MutableStateFlow(VoipSettingsUiState())

        /** Screen state. */
        val uiState: StateFlow<VoipSettingsUiState> =
            combine(local, account.signedIn, videos.clipsOnMobileData, flash.recent().map { it.getOrNull().orEmpty() }) { s, signedIn, mobile, recent ->
                s.copy(signedIn = signedIn, clipsOnMobileData = mobile, recent = recent)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), VoipSettingsUiState())

        init {
            viewModelScope.launch { if (account.signedIn.first()) refresh() }
        }

        /** Handles [event]. */
        fun onEvent(event: VoipSettingsEvent) {
            viewModelScope.launch {
                when (event) {
                    is VoipSettingsEvent.SetReachable -> run(calls.setReachable(event.reachable)) { s, r -> s.copy(reachable = r) }
                    is VoipSettingsEvent.SetClipsOnMobileData -> videos.setClipsOnMobileData(event.allowed)
                    is VoipSettingsEvent.Upload -> upload(File(event.path))
                    VoipSettingsEvent.DeleteVideo -> run(videos.delete()) { s, _ -> s.copy(video = NO_VIDEO) }
                    VoipSettingsEvent.Refresh -> refresh()
                }
            }
        }

        private suspend fun refresh() {
            run(calls.reachable()) { s, r -> s.copy(reachable = r) }
            run(videos.mine()) { s, v -> s.copy(video = v) }
        }

        private suspend fun upload(file: File) {
            local.update { it.copy(busy = true, error = null) }
            val result = videos.upload(file)
            file.delete()
            local.update { it.copy(busy = false) }
            run(result) { s, v -> s.copy(video = v) }
        }

        private fun <T> run(
            result: AppResult<T>,
            apply: (VoipSettingsUiState, T) -> VoipSettingsUiState,
        ) {
            when (result) {
                is AppResult.Success -> local.update { apply(it, result.value).copy(error = null) }
                is AppResult.Failure -> local.update { it.copy(error = result.error.message()) }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            val NO_VIDEO = MyVideoCallerId(videoId = null, job = VideoJob.NONE, error = null, hidden = false)
        }
    }
