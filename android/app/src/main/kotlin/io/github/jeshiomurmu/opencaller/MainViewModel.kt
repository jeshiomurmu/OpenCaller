package io.github.jeshiomurmu.opencaller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.model.AccessItem
import io.github.jeshiomurmu.opencaller.core.model.AccessItemStatus
import io.github.jeshiomurmu.opencaller.core.model.OnboardingStep
import io.github.jeshiomurmu.opencaller.core.telephony.AccessStatusSource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * App-level state.
 *
 * @property degraded OpenCaller can't screen calls (call screening role missing): show the banner.
 * @property defaultPhoneApp status of the default phone app role when the Calls tab should offer it, else null.
 */
data class AppUiState(
    val loading: Boolean = true,
    val showOnboarding: Boolean = false,
    val degraded: Boolean = false,
    val defaultPhoneApp: AccessItemStatus? = null,
)

/** Decides between onboarding and the app shell, and what the shell warns about. */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        settings: UserSettingsDataSource,
        access: AccessStatusSource,
    ) : ViewModel() {
        /** App state. */
        val uiState: StateFlow<AppUiState> =
            combine(settings.preferences, access.status) { preferences, status ->
                AppUiState(
                    loading = false,
                    showOnboarding = preferences.onboardingStep != OnboardingStep.DONE,
                    degraded = status.isDegraded,
                    defaultPhoneApp =
                        status
                            .statusOf(AccessItem.DEFAULT_PHONE_APP)
                            .takeIf { it == AccessItemStatus.NOT_GRANTED || it == AccessItemStatus.COMING_LATER },
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AppUiState())

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
