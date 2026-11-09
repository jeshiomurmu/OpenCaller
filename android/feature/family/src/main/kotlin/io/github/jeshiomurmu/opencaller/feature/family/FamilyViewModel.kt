package io.github.jeshiomurmu.opencaller.feature.family

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.account.AccountStatus
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyGroups
import io.github.jeshiomurmu.opencaller.core.model.FamilyGroup
import io.github.jeshiomurmu.opencaller.core.model.FamilyInvite
import io.github.jeshiomurmu.opencaller.core.model.FamilyInvitePreview
import io.github.jeshiomurmu.opencaller.core.model.FamilySharing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The family protection screen.
 *
 * @property group the user's group, or `null` when they are in none (or before it loaded).
 * @property preview an invite the user is looking at on the consent screen.
 * @property invite a code the admin just made, shown once.
 * @property error the last failure's text.
 */
data class FamilyUiState(
    val signedIn: Boolean = false,
    val loading: Boolean = true,
    val busy: Boolean = false,
    val group: FamilyGroup? = null,
    val preview: FamilyInvitePreview? = null,
    val invite: FamilyInvite? = null,
    @param:StringRes val error: Int? = null,
)

/** What the family screen can do. */
sealed interface FamilyEvent {
    /** Read the group from the server again. */
    data object Refresh : FamilyEvent

    /** Create a group named [name]; members see the admin as [label]. */
    data class Create(
        val name: String,
        val label: String,
    ) : FamilyEvent

    /** Look at what joining with [code] means (opens the consent screen). */
    data class OpenInvite(
        val code: String,
    ) : FamilyEvent

    /** Close the consent screen without joining. */
    data object CancelJoin : FamilyEvent

    /** Join the previewed group as [label] sharing [sharing]; [consented] is the member's own tick. */
    data class Join(
        val label: String,
        val sharing: FamilySharing,
        val consented: Boolean,
    ) : FamilyEvent

    /** Change what this member shares. */
    data class SetSharing(
        val sharing: FamilySharing,
    ) : FamilyEvent

    /** Make an invite code (admin). */
    data object Invite : FamilyEvent

    /** Hide the code shown after [Invite]. */
    data object InviteShown : FamilyEvent

    /** Leave the group (the admin: end it). */
    data object Leave : FamilyEvent

    /** Remove member [memberId] (admin). */
    data class Remove(
        val memberId: String,
    ) : FamilyEvent
}

/** Family groups: create, invite, join with consent, sharing switches, members, alerts and counts. */
@HiltViewModel
class FamilyViewModel
    @Inject
    constructor(
        account: AccountStatus,
        private val family: FamilyGroups,
    ) : ViewModel() {
        private val state = MutableStateFlow(FamilyUiState())

        /** Screen state. */
        val uiState: StateFlow<FamilyUiState> = state.asStateFlow()

        init {
            viewModelScope.launch {
                account.signedIn.distinctUntilChanged().collect { signedIn ->
                    state.update { it.copy(signedIn = signedIn, loading = signedIn) }
                    if (signedIn) refresh()
                }
            }
        }

        /** Handles [event]. */
        fun onEvent(event: FamilyEvent) {
            viewModelScope.launch {
                when (event) {
                    FamilyEvent.Refresh -> refresh()
                    is FamilyEvent.Create -> settle(family.create(event.name, event.label)) { s, g -> s.copy(group = g) }
                    is FamilyEvent.OpenInvite -> settle(family.preview(event.code)) { s, p -> s.copy(preview = p) }
                    FamilyEvent.CancelJoin -> state.update { it.copy(preview = null, error = null) }
                    is FamilyEvent.Join -> join(event)
                    is FamilyEvent.SetSharing -> setSharing(event.sharing)
                    FamilyEvent.Invite -> settle(family.invite()) { s, i -> s.copy(invite = i) }
                    FamilyEvent.InviteShown -> state.update { it.copy(invite = null) }
                    FamilyEvent.Leave -> settle(family.leave()) { s, _ -> s.copy(group = null, invite = null) }
                    is FamilyEvent.Remove -> settle(family.remove(event.memberId)) { s, g -> s.copy(group = g) }
                }
            }
        }

        private suspend fun refresh() {
            state.update { it.copy(loading = true) }
            val result = family.refresh()
            state.update { it.copy(loading = false) }
            settle(result) { s, g -> s.copy(group = g) }
        }

        private suspend fun join(event: FamilyEvent.Join) {
            val preview = state.value.preview ?: return
            settle(family.join(preview, event.label, event.sharing, event.consented)) { s, g -> s.copy(group = g, preview = null) }
        }

        private suspend fun setSharing(sharing: FamilySharing) {
            val group = state.value.group ?: return
            settle(family.update(group.myLabel, sharing)) { s, g -> s.copy(group = g) }
        }

        private suspend fun <T> settle(
            result: AppResult<T>,
            apply: (FamilyUiState, T) -> FamilyUiState,
        ) {
            state.update { it.copy(busy = true) }
            when (result) {
                is AppResult.Success -> state.update { apply(it, result.value).copy(busy = false, error = null) }
                is AppResult.Failure -> state.update { it.copy(busy = false, error = result.error.message()) }
            }
        }
    }
