package io.github.jeshiomurmu.opencaller.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.profile.Profiles
import io.github.jeshiomurmu.opencaller.core.model.ContactRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Pending requests for the user's name (docs/prd.md F-SO-05); [busy] holds the ids being answered. */
data class ContactRequestsUiState(
    val loading: Boolean = true,
    val requests: List<ContactRequest> = emptyList(),
    val busy: Set<Long> = emptySet(),
    val problem: ProfileProblem? = null,
)

/** Loads the requests and approves or denies them; an answered request leaves the list. */
@HiltViewModel
class ContactRequestsViewModel
    @Inject
    constructor(
        private val profiles: Profiles,
    ) : ViewModel() {
        private val state = MutableStateFlow(ContactRequestsUiState())

        /** Screen state. */
        val uiState: StateFlow<ContactRequestsUiState> = state.asStateFlow()

        /** (Re)loads the requests. */
        fun load() {
            state.update { it.copy(loading = true, problem = null) }
            viewModelScope.launch {
                val result = profiles.contactRequests()
                state.update {
                    when (result) {
                        is AppResult.Success -> it.copy(loading = false, requests = result.value)
                        is AppResult.Failure -> it.copy(loading = false, problem = result.error.toProblem())
                    }
                }
            }
        }

        /** Approves ([approve]) or denies request [id]. */
        fun answer(
            id: Long,
            approve: Boolean,
        ) {
            if (id in state.value.busy) return
            state.update { it.copy(busy = it.busy + id, problem = null) }
            viewModelScope.launch {
                val result = profiles.decide(id, approve)
                state.update {
                    when (result) {
                        is AppResult.Success -> it.copy(busy = it.busy - id, requests = it.requests.filterNot { r -> r.id == id })
                        is AppResult.Failure -> it.copy(busy = it.busy - id, problem = result.error.toProblem())
                    }
                }
            }
        }
    }
