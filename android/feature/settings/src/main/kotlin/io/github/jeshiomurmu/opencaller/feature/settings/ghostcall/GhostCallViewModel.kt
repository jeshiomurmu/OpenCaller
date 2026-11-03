package io.github.jeshiomurmu.opencaller.feature.settings.ghostcall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.supporter.EntitlementProvider
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.model.GhostCallSetup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings → Ghost Call.
 *
 * @property scheduledSeconds the delay of the call that is waiting to ring, `null` when none is.
 */
data class GhostCallUiState(
    val entitled: Boolean = false,
    val setup: GhostCallSetup = GhostCallSetup(),
    val scheduledSeconds: Int? = null,
    val photoFailed: Boolean = false,
)

/** What the user did on the Ghost Call screen. */
sealed interface GhostCallEvent {
    /** The caller's name changed. */
    data class Name(
        val text: String,
    ) : GhostCallEvent

    /** The number shown changed. */
    data class Number(
        val text: String,
    ) : GhostCallEvent

    /** Another delay was chosen. */
    data class Delay(
        val seconds: Int,
    ) : GhostCallEvent

    /** A photo was picked ([uri] from the photo picker). */
    data class PhotoPicked(
        val uri: String,
    ) : GhostCallEvent

    /** Remove the photo. */
    data object RemovePhoto : GhostCallEvent

    /** Ring after the delay. */
    data object Schedule : GhostCallEvent

    /** Don't ring after all. */
    data object Cancel : GhostCallEvent
}

/** Sets up and schedules the fake call; nothing about it ever becomes a real call. */
@HiltViewModel
class GhostCallViewModel
    @Inject
    constructor(
        private val settings: UserSettingsDataSource,
        entitlements: EntitlementProvider,
        private val calls: GhostCalls,
        private val photos: GhostCallPhotos,
    ) : ViewModel() {
        private val local = MutableStateFlow(GhostCallUiState())

        /** Screen state. */
        val uiState: StateFlow<GhostCallUiState> =
            combine(local, settings.preferences, entitlements.entitlement) { state, prefs, entitlement ->
                state.copy(entitled = entitlement.entitled, setup = prefs.ghostCall)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), GhostCallUiState())

        /** Handles [event]. */
        fun onEvent(event: GhostCallEvent) {
            when (event) {
                is GhostCallEvent.Name -> {
                    edit { it.copy(callerName = event.text.take(GhostCallSetup.MAX_NAME_LENGTH)) }
                }

                is GhostCallEvent.Number -> {
                    edit {
                        it.copy(
                            displayNumber = event.text.filter(::isNumberChar).take(GhostCallSetup.MAX_NUMBER_LENGTH),
                        )
                    }
                }

                is GhostCallEvent.Delay -> {
                    if (event.seconds in GhostCallSetup.DELAYS) edit { it.copy(delaySeconds = event.seconds) }
                }

                is GhostCallEvent.PhotoPicked -> {
                    pickPhoto(event.uri)
                }

                GhostCallEvent.RemovePhoto -> {
                    removePhoto()
                }

                GhostCallEvent.Schedule -> {
                    schedule()
                }

                GhostCallEvent.Cancel -> {
                    cancel()
                }
            }
        }

        private fun schedule() {
            val state = uiState.value
            if (!state.entitled) return
            calls.schedule(state.setup.delaySeconds)
            local.update { it.copy(scheduledSeconds = state.setup.delaySeconds) }
        }

        private fun cancel() {
            calls.cancel()
            local.update { it.copy(scheduledSeconds = null) }
        }

        private fun pickPhoto(uri: String) {
            viewModelScope.launch {
                val saved = photos.save(uri)
                local.update { it.copy(photoFailed = saved is AppResult.Failure) }
                if (saved is AppResult.Success) edit { it.copy(hasPhoto = true) }
            }
        }

        private fun removePhoto() {
            viewModelScope.launch {
                photos.delete()
                edit { it.copy(hasPhoto = false) }
            }
        }

        private fun edit(change: (GhostCallSetup) -> GhostCallSetup) {
            viewModelScope.launch { settings.update { it.copy(ghostCall = change(it.ghostCall)) } }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L

            // The number is only shown, never dialled: digits, spaces and the usual punctuation.
            fun isNumberChar(c: Char): Boolean = c.isDigit() || c in " +-()."
        }
    }
