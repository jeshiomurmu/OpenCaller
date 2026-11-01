package io.github.jeshiomurmu.opencaller.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.profile.Profiles
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.PublicProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Another number's profile as this user may see it. Without a name the user can ask for it (a contact request);
 * [requestSent] is then true, whatever the owner does, because the server never tells.
 */
data class PublicProfileUiState(
    val number: PhoneNumber? = null,
    val loading: Boolean = true,
    val profile: PublicProfile? = null,
    val picture: PictureBytes? = null,
    val requesting: Boolean = false,
    val requestSent: Boolean = false,
    val problem: ProfileProblem? = null,
)

/** Opens a profile (the server records the view unless the user is incognito) and sends contact requests. */
@HiltViewModel
class PublicProfileViewModel
    @Inject
    constructor(
        private val profiles: Profiles,
    ) : ViewModel() {
        private val state = MutableStateFlow(PublicProfileUiState())

        /** Screen state. */
        val uiState: StateFlow<PublicProfileUiState> = state.asStateFlow()

        /** Opens the profile of [e164]; a number that isn't E.164 shows nothing. */
        fun open(e164: String) {
            if (!PhoneNumber.isE164(e164)) {
                state.value = PublicProfileUiState(loading = false, problem = ProfileProblem.SERVER)
                return
            }
            val number = PhoneNumber(e164)
            if (state.value.number == number && state.value.profile != null) return
            state.value = PublicProfileUiState(number = number)
            viewModelScope.launch {
                when (val result = profiles.profile(number)) {
                    is AppResult.Success -> {
                        state.update { it.copy(loading = false, profile = result.value) }
                        result.value.avatarUrl?.let { url ->
                            (profiles.picture(url) as? AppResult.Success)?.let { bytes ->
                                state.update { it.copy(picture = PictureBytes(bytes.value)) }
                            }
                        }
                    }

                    is AppResult.Failure -> {
                        state.update { it.copy(loading = false, problem = result.error.toProblem()) }
                    }
                }
            }
        }

        /** Asks the owner for their name. */
        fun askForName() {
            val number = state.value.number ?: return
            if (state.value.requesting || state.value.requestSent) return
            state.update { it.copy(requesting = true, problem = null) }
            viewModelScope.launch {
                val result = profiles.requestContact(number)
                state.update {
                    when (result) {
                        is AppResult.Success -> it.copy(requesting = false, requestSent = true)
                        is AppResult.Failure -> it.copy(requesting = false, problem = result.error.toProblem())
                    }
                }
            }
        }
    }
