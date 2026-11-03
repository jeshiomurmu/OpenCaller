package io.github.jeshiomurmu.opencaller.feature.business

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.business.BusinessClaims
import io.github.jeshiomurmu.opencaller.core.model.BusinessProfile
import io.github.jeshiomurmu.opencaller.core.model.isBusinessProfileId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A logo's bytes (PNG or JPEG), compared by content so recomposition doesn't decode it again. */
class LogoBytes(
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is LogoBytes && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()
}

/** A verified business's profile screen (docs/prd.md F-BZ-02). */
data class BusinessProfileUiState(
    val loading: Boolean = true,
    val profile: BusinessProfile? = null,
    val logo: LogoBytes? = null,
    val problem: BusinessProblem? = null,
)

/** Loads a verified business's public profile and logo (no sign-in; the request names only the business). */
@HiltViewModel
class BusinessProfileViewModel
    @Inject
    constructor(
        private val claims: BusinessClaims,
    ) : ViewModel() {
        private val state = MutableStateFlow(BusinessProfileUiState())
        private var opened: String? = null

        /** Screen state. */
        val uiState: StateFlow<BusinessProfileUiState> = state.asStateFlow()

        /** Opens business [businessId]; anything that isn't a business id shows "not found". */
        fun open(businessId: String) {
            if (opened == businessId) return
            opened = businessId
            if (!isBusinessProfileId(businessId)) {
                state.value = BusinessProfileUiState(loading = false, problem = BusinessProblem.NotFound)
                return
            }
            state.value = BusinessProfileUiState()
            viewModelScope.launch {
                when (val result = claims.profile(businessId)) {
                    is AppResult.Success -> {
                        state.update { it.copy(loading = false, profile = result.value) }
                        result.value.logoUrl?.let { loadLogo(it) }
                    }

                    is AppResult.Failure -> {
                        state.update { it.copy(loading = false, problem = result.error.toBusinessProblem()) }
                    }
                }
            }
        }

        // A logo that fails to load leaves the initial in its place; the profile still shows.
        private suspend fun loadLogo(path: String) {
            val bytes = (claims.logo(path) as? AppResult.Success)?.value ?: return
            state.update { it.copy(logo = LogoBytes(bytes)) }
        }
    }
