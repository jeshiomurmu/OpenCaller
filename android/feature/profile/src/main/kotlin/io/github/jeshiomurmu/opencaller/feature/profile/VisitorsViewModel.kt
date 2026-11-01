package io.github.jeshiomurmu.opencaller.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.profile.Profiles
import io.github.jeshiomurmu.opencaller.core.data.profile.SUPPORTER_REQUIRED
import io.github.jeshiomurmu.opencaller.core.model.ProfileVisits
import io.github.jeshiomurmu.opencaller.core.model.VisitKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Who viewed my profile" or "Who searched for me" (docs/prd.md F-SO-02/03).
 *
 * @property supporterRequired true when the server says this is a supporter feature (the list is not shown).
 */
data class VisitorsUiState(
    val kind: VisitKind = VisitKind.VIEW,
    val loading: Boolean = true,
    val supporterRequired: Boolean = false,
    val visits: ProfileVisits? = null,
    val problem: ProfileProblem? = null,
)

/** Loads one list of visitors. */
@HiltViewModel
class VisitorsViewModel
    @Inject
    constructor(
        private val profiles: Profiles,
    ) : ViewModel() {
        private val state = MutableStateFlow(VisitorsUiState())

        /** Screen state. */
        val uiState: StateFlow<VisitorsUiState> = state.asStateFlow()

        /** Shows (or reloads) the visitors of [kind]. */
        fun open(kind: VisitKind) {
            state.value = VisitorsUiState(kind = kind)
            viewModelScope.launch {
                val result = profiles.visits(kind)
                state.update {
                    when (result) {
                        is AppResult.Success -> {
                            it.copy(loading = false, visits = result.value)
                        }

                        is AppResult.Failure -> {
                            if (result.error == AppError.Validation(SUPPORTER_REQUIRED)) {
                                it.copy(loading = false, supporterRequired = true)
                            } else {
                                it.copy(loading = false, problem = result.error.toProblem())
                            }
                        }
                    }
                }
            }
        }
    }
