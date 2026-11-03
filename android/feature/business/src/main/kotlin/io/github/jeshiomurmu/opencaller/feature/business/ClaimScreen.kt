package io.github.jeshiomurmu.opencaller.feature.business

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.BusinessCategory
import io.github.jeshiomurmu.opencaller.core.model.BusinessClaim
import io.github.jeshiomurmu.opencaller.core.model.ClaimMethod
import io.github.jeshiomurmu.opencaller.core.model.ClaimStatus
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber

/** The claim flow wired to its view model; [onSignIn] opens sign-in. */
@Composable
fun ClaimRoute(
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ClaimViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.signedIn) { viewModel.onEvent(ClaimEvent.Refresh) }
    val documents =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.onEvent(ClaimEvent.AddDocument(it.toString())) }
        }
    val logo =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { viewModel.onEvent(ClaimEvent.SetLogo(it.toString())) }
        }
    ClaimScreen(
        state = state,
        onEvent = viewModel::onEvent,
        actions =
            ClaimActions(
                onSignIn = onSignIn,
                onPickDocument = { documents.launch(DOCUMENT_TYPES) },
                onPickLogo = { logo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            ),
        modifier = modifier,
    )
}

/** What the claim screen opens outside itself. */
class ClaimActions(
    val onSignIn: () -> Unit = {},
    val onPickDocument: () -> Unit = {},
    val onPickLogo: () -> Unit = {},
)

/** The claim flow: the user's claims, then the form, the code and the documents (docs/prd.md F-BZ-01). */
@Composable
fun ClaimScreen(
    state: ClaimUiState,
    onEvent: (ClaimEvent) -> Unit,
    actions: ClaimActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        state.problem?.let { ProblemLine(it, onDismiss = { onEvent(ClaimEvent.DismissProblem) }) }
        when {
            !state.serverConfigured -> Text(stringResource(R.string.claim_not_connected), style = MaterialTheme.typography.bodyLarge)
            !state.signedIn -> SignInFirst(actions.onSignIn)
            state.step == ClaimStep.LIST -> ClaimList(state, onEvent)
            state.step == ClaimStep.FORM -> ClaimFormStep(state, onEvent)
            state.step == ClaimStep.CODE -> CodeStep(state, onEvent)
            else -> DocumentsStep(state, onEvent, actions)
        }
        if (state.busy) CircularProgressIndicator()
    }
}

@Composable
private fun SignInFirst(onSignIn: () -> Unit) {
    Heading(stringResource(R.string.claim_title))
    Text(stringResource(R.string.claim_intro), style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(R.string.claim_sign_in), style = MaterialTheme.typography.bodyLarge)
    Button(onClick = onSignIn) { Text(stringResource(R.string.claim_sign_in_button)) }
}

@Composable
private fun ClaimList(
    state: ClaimUiState,
    onEvent: (ClaimEvent) -> Unit,
) {
    Heading(stringResource(R.string.claim_title))
    Text(stringResource(R.string.claim_intro), style = MaterialTheme.typography.bodyLarge)
    Button(onClick = { onEvent(ClaimEvent.NewClaim) }, enabled = !state.busy) { Text(stringResource(R.string.claim_new)) }
    when {
        state.loading -> CircularProgressIndicator()
        state.claims.isEmpty() -> Text(stringResource(R.string.claim_none), style = MaterialTheme.typography.bodyMedium)
        else -> state.claims.forEach { ClaimRow(it, busy = state.busy, onEvent = onEvent) }
    }
}

@Composable
private fun ClaimRow(
    claim: BusinessClaim,
    busy: Boolean,
    onEvent: (ClaimEvent) -> Unit,
) {
    HorizontalDivider()
    Column(verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xxs)) {
        Text(claim.displayName, style = MaterialTheme.typography.titleMedium)
        Text(claim.number.e164, style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(claim.status.labelRes()), style = MaterialTheme.typography.bodyMedium)
        claim.reviewNote?.let { Text(stringResource(R.string.claim_review_note, it), style = MaterialTheme.typography.bodyMedium) }
        if (claim.status.isOpen) {
            Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
                if (claim.status != ClaimStatus.SUBMITTED) {
                    Button(onClick = { onEvent(ClaimEvent.Continue(claim)) }, enabled = !busy) {
                        Text(stringResource(R.string.claim_continue))
                    }
                }
                OutlinedButton(onClick = { onEvent(ClaimEvent.Withdraw(claim.id)) }, enabled = !busy) {
                    Text(stringResource(R.string.claim_withdraw))
                }
            }
        }
    }
}

/** The screen's heading. */
@Composable
internal fun Heading(text: String) {
    Text(text = text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
}

/** A problem with its OK button. */
@Composable
internal fun ProblemLine(
    problem: BusinessProblem,
    onDismiss: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(OpenCallerSpacing.s),
            horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
        ) {
            Text(stringResource(problem.messageRes()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.business_dismiss)) }
        }
    }
}

// What OpenDocument offers: the types the server accepts for documents.
private val DOCUMENT_TYPES = arrayOf("application/pdf", "image/jpeg", "image/png")

private fun previewClaim(
    id: String,
    name: String,
    status: ClaimStatus,
    note: String? = null,
) = BusinessClaim(id, PhoneNumber("+8801812345678"), name, BusinessCategory.BANK, ClaimMethod.SMS, status, null, note, null, 0)

private val PREVIEW_CLAIMS =
    listOf(
        previewClaim("c1", "Example Bank", ClaimStatus.SUBMITTED),
        previewClaim("c2", "Example Shop", ClaimStatus.REJECTED, note = "Blurry document"),
    )

@OpenCallerPreviews
@Composable
private fun ClaimListPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ClaimScreen(
                state = ClaimUiState(loading = false, signedIn = true, claims = PREVIEW_CLAIMS),
                onEvent = {},
                actions = ClaimActions(),
            )
        }
    }
}

@OpenCallerPreviews
@Composable
private fun ClaimSignInPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ClaimScreen(state = ClaimUiState(loading = false), onEvent = {}, actions = ClaimActions())
        }
    }
}
