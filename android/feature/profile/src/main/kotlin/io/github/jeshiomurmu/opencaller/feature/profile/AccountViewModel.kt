package io.github.jeshiomurmu.opencaller.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.data.account.Account
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The account section of the Me tab.
 *
 * @property unlistPageUrl the server's "unlist my number" page, or `null` without a server.
 */
data class AccountUiState(
    val loading: Boolean = true,
    val serverConfigured: Boolean = false,
    val signedIn: Boolean = false,
    val unlistPageUrl: String? = null,
)

/** Sign-in status, sign-out and the unlist page. */
@HiltViewModel
class AccountViewModel
    @Inject
    constructor(
        private val account: Account,
    ) : ViewModel() {
        /**
         * Section state. Whether there is a server is known at once (it comes from the build), so the first frame
         * already shows the right rows: no "Not connected" flash and no jump when the sign-in state arrives.
         */
        val uiState: StateFlow<AccountUiState> =
            account.signedIn
                .map { signedIn -> initialState().copy(loading = false, signedIn = signedIn) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), initialState())

        private fun initialState() =
            AccountUiState(
                loading = true,
                serverConfigured = account.serverConfigured,
                unlistPageUrl = account.unlistPageUrl,
            )

        /** Signs out on this phone. */
        fun signOut() {
            viewModelScope.launch { account.signOut() }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
