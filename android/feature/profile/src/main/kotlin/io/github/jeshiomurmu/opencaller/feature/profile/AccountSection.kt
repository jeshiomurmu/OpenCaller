package io.github.jeshiomurmu.opencaller.feature.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme

/** The Me tab's account section, wired to its view model. */
@Composable
fun AccountRoute(
    onSignIn: () -> Unit,
    onSearch: () -> Unit,
    onDispute: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    AccountSection(
        state = state,
        actions =
            AccountActions(
                onSignIn = onSignIn,
                onSignOut = viewModel::signOut,
                onSearch = onSearch,
                onDispute = onDispute,
                onUnlist = { state.unlistPageUrl?.let(uri::openUri) },
            ),
        modifier = modifier,
    )
}

/** What the account section's rows do. */
data class AccountActions(
    val onSignIn: () -> Unit,
    val onSignOut: () -> Unit,
    val onSearch: () -> Unit,
    val onDispute: () -> Unit,
    val onUnlist: () -> Unit,
)

/**
 * Account: sign in (optional) or out, number search, and the dispute and unlist flows. Without a server, a single
 * line says the app isn't connected; blocking works anyway.
 */
@Composable
fun AccountSection(
    state: AccountUiState,
    actions: AccountActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = OpenCallerSpacing.xs)) {
        Text(
            text = stringResource(R.string.account_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = OpenCallerSpacing.screenPadding).semantics { heading() },
        )
        if (!state.serverConfigured) {
            AccountRow(stringResource(R.string.not_connected_title), stringResource(R.string.not_connected_body), onClick = null)
        } else {
            if (state.signedIn) {
                AccountRow(stringResource(R.string.account_signed_in), stringResource(R.string.account_sign_out), actions.onSignOut)
            } else {
                AccountRow(stringResource(R.string.account_sign_in), stringResource(R.string.account_sign_in_summary), actions.onSignIn)
            }
            AccountRow(stringResource(R.string.account_search), stringResource(R.string.account_search_summary), actions.onSearch)
            AccountRow(stringResource(R.string.account_dispute), stringResource(R.string.account_dispute_summary), actions.onDispute)
            AccountRow(stringResource(R.string.account_unlist), stringResource(R.string.account_unlist_summary), actions.onUnlist)
        }
    }
}

@Composable
private fun AccountRow(
    title: String,
    summary: String,
    onClick: (() -> Unit)?,
) {
    val clickable = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = OpenCallerSpacing.minTouchTarget)
                .then(clickable)
                .padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.s),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(text = summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OpenCallerPreviews
@Composable
private fun AccountSectionPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            AccountSection(
                state =
                    AccountUiState(
                        loading = false,
                        serverConfigured = true,
                        signedIn = false,
                        unlistPageUrl = "https://example.org/unlist",
                    ),
                actions = AccountActions({}, {}, {}, {}, {}),
            )
        }
    }
}
