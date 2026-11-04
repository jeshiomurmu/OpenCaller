package io.github.jeshiomurmu.opencaller.feature.settings.extras

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.data.supporter.EntitlementProvider
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.model.AnnounceCallerMode
import io.github.jeshiomurmu.opencaller.core.model.ThemeMode
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import io.github.jeshiomurmu.opencaller.core.telephony.NotificationListenerAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings → Appearance, Announce caller, and WhatsApp/Telegram/Signal calls.
 *
 * @property goldTheme the user's choice (it only shows while [entitled]).
 * @property notificationAccess OpenCaller may read notifications (system settings).
 */
data class ExtrasUiState(
    val entitled: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val goldTheme: Boolean = false,
    val announceMode: AnnounceCallerMode = AnnounceCallerMode.OFF,
    val messengerCallerId: Boolean = false,
    val notificationAccess: Boolean = false,
)

/** What the user changed. */
sealed interface ExtrasEvent {
    /** Light, dark or system. */
    data class Theme(
        val mode: ThemeMode,
    ) : ExtrasEvent

    /** Gold accents on or off. */
    data class Gold(
        val on: Boolean,
    ) : ExtrasEvent

    /** When to announce callers. */
    data class Announce(
        val mode: AnnounceCallerMode,
    ) : ExtrasEvent

    /** Messenger caller ID on or off. */
    data class Messenger(
        val on: Boolean,
    ) : ExtrasEvent

    /** The screen is back in front (notification access may have changed in system settings). */
    data object Resumed : ExtrasEvent
}

/** Reads and writes the supporter extras' settings; supporter-only choices can be made only while entitled. */
@HiltViewModel
class ExtrasViewModel
    @Inject
    constructor(
        private val settings: UserSettingsDataSource,
        entitlements: EntitlementProvider,
        private val listenerAccess: NotificationListenerAccess,
    ) : ViewModel() {
        private val access = MutableStateFlow(listenerAccess.granted())

        /** Screen state. */
        val uiState: StateFlow<ExtrasUiState> =
            combine(settings.preferences, entitlements.entitlement, access) { prefs, entitlement, granted ->
                ExtrasUiState(
                    entitled = entitlement.entitled,
                    themeMode = prefs.themeMode,
                    goldTheme = prefs.goldTheme,
                    announceMode = prefs.announceCallerMode,
                    messengerCallerId = prefs.messengerCallerId,
                    notificationAccess = granted,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), ExtrasUiState())

        /** The system page where notification access is given. */
        fun notificationAccessIntent() = listenerAccess.settingsIntent()

        /** Handles [event]. */
        fun onEvent(event: ExtrasEvent) {
            val entitled = uiState.value.entitled
            when (event) {
                is ExtrasEvent.Theme -> {
                    update { it.copy(themeMode = event.mode) }
                }

                is ExtrasEvent.Gold -> {
                    if (entitled || !event.on) update { it.copy(goldTheme = event.on) }
                }

                is ExtrasEvent.Announce -> {
                    if (entitled ||
                        event.mode == AnnounceCallerMode.OFF
                    ) {
                        update { it.copy(announceCallerMode = event.mode) }
                    }
                }

                is ExtrasEvent.Messenger -> {
                    if (entitled || !event.on) update { it.copy(messengerCallerId = event.on) }
                }

                ExtrasEvent.Resumed -> {
                    access.value = listenerAccess.granted()
                }
            }
        }

        private fun update(change: (UserPreferences) -> UserPreferences) {
            viewModelScope.launch { settings.update(change) }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
