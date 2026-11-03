package io.github.jeshiomurmu.opencaller.feature.business

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.data.account.AccountStatus
import io.github.jeshiomurmu.opencaller.core.data.business.BusinessClaims
import io.github.jeshiomurmu.opencaller.core.model.BusinessCategory
import io.github.jeshiomurmu.opencaller.core.model.BusinessClaim
import io.github.jeshiomurmu.opencaller.core.model.ClaimFileKind
import io.github.jeshiomurmu.opencaller.core.model.ClaimForm
import io.github.jeshiomurmu.opencaller.core.model.ClaimMethod
import io.github.jeshiomurmu.opencaller.core.model.ClaimStatus
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the claim screen is: the user's claims, the business form, the code, or the documents. */
enum class ClaimStep { LIST, FORM, CODE, DOCUMENTS }

/** What the claimant typed. A claim can start once the number, both names and a category are filled in. */
data class ClaimFields(
    val number: String = "",
    val legalName: String = "",
    val displayName: String = "",
    val category: BusinessCategory? = null,
    val website: String = "",
    val hours: String = "",
    val callReason: String = "",
    val method: ClaimMethod = ClaimMethod.SMS,
) {
    /** True when every required field is filled in. */
    val complete: Boolean get() = number.isNotBlank() && legalName.isNotBlank() && displayName.isNotBlank() && category != null
}

/** A file uploaded for the active claim: its name for the list, its key for submit. */
data class UploadedFile(
    val name: String,
    val key: String,
)

/** The claim screen (docs/prd.md F-BZ-01). [active] is the claim being worked on in the CODE and DOCUMENTS steps. */
data class ClaimUiState(
    val loading: Boolean = true,
    val serverConfigured: Boolean = true,
    val signedIn: Boolean = false,
    val step: ClaimStep = ClaimStep.LIST,
    val claims: List<BusinessClaim> = emptyList(),
    val fields: ClaimFields = ClaimFields(),
    val active: BusinessClaim? = null,
    val code: String = "",
    val documents: List<UploadedFile> = emptyList(),
    val logo: UploadedFile? = null,
    val busy: Boolean = false,
    val problem: BusinessProblem? = null,
) {
    /** The form can be sent. */
    val canStart: Boolean get() = fields.complete && !busy

    /** The code can be checked (six digits). */
    val canVerify: Boolean get() = code.length == CODE_LENGTH && !busy

    /** Another document fits. */
    val canAddDocument: Boolean get() = documents.size < ClaimFileKind.MAX_DOCUMENTS && !busy

    /** The claim can go to review (at least one document). */
    val canSubmit: Boolean get() = documents.isNotEmpty() && !busy

    /** Constants. */
    companion object {
        /** Digits in a claim code. */
        const val CODE_LENGTH = 6
    }
}

/** What the user did on the claim screen. */
sealed interface ClaimEvent {
    /** Load the user's claims again. */
    data object Refresh : ClaimEvent

    /** Open the empty form. */
    data object NewClaim : ClaimEvent

    /** Go on with [claim] (enter its code, or add its documents). */
    data class Continue(
        val claim: BusinessClaim,
    ) : ClaimEvent

    /** The form changed. */
    data class EditFields(
        val fields: ClaimFields,
    ) : ClaimEvent

    /** Send the form: the code goes to the business number. */
    data object Start : ClaimEvent

    /** The code field changed. */
    data class EditCode(
        val code: String,
    ) : ClaimEvent

    /** Check the code. */
    data object Verify : ClaimEvent

    /** Send a new code by [method]. */
    data class Resend(
        val method: ClaimMethod,
    ) : ClaimEvent

    /** The user picked a document at content [uri]. */
    data class AddDocument(
        val uri: String,
    ) : ClaimEvent

    /** Drop document [key] from this submission (it stays on the server until the claim is decided). */
    data class RemoveDocument(
        val key: String,
    ) : ClaimEvent

    /** The user picked a logo at content [uri]. */
    data class SetLogo(
        val uri: String,
    ) : ClaimEvent

    /** Send the claim for review. */
    data object Submit : ClaimEvent

    /** Withdraw claim [id]. */
    data class Withdraw(
        val id: String,
    ) : ClaimEvent

    /** Back to the list (the claim stays where it is and can be continued). */
    data object Back : ClaimEvent

    /** The problem was read. */
    data object DismissProblem : ClaimEvent
}

/**
 * Claims a business number: the form, the code sent to that number (by SMS or a phone call), the documents and an
 * optional logo, then review. Every step is checked by the server again; this only keeps the screen honest.
 */
@HiltViewModel
class ClaimViewModel
    @Inject
    constructor(
        private val claims: BusinessClaims,
        private val files: ClaimFileReader,
        private val account: AccountStatus,
        private val normalizer: PhoneNumberNormalizer,
        private val homeRegion: HomeRegionProvider,
    ) : ViewModel() {
        private val state = MutableStateFlow(ClaimUiState(serverConfigured = account.serverConfigured))

        /** Screen state. */
        val uiState: StateFlow<ClaimUiState> =
            combine(account.signedIn, state) { signedIn, local -> local.copy(signedIn = signedIn) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), state.value)

        /** Handles [event]: on the screen only, or with the server. */
        fun onEvent(event: ClaimEvent) {
            val local = edited(state.value, event)
            if (local != null) state.value = local else act(event)
        }

        private fun act(event: ClaimEvent) {
            when (event) {
                ClaimEvent.Refresh -> {
                    refresh()
                }

                ClaimEvent.Start -> {
                    start()
                }

                ClaimEvent.Verify -> {
                    verify()
                }

                is ClaimEvent.Resend -> {
                    resend(event.method)
                }

                is ClaimEvent.AddDocument -> {
                    upload(event.uri, ClaimFileKind.DOCUMENT)
                }

                is ClaimEvent.SetLogo -> {
                    upload(event.uri, ClaimFileKind.LOGO)
                }

                ClaimEvent.Submit -> {
                    submit()
                }

                is ClaimEvent.Withdraw -> {
                    withdraw(event.id)
                }

                ClaimEvent.Back -> {
                    state.update { it.copy(step = ClaimStep.LIST, problem = null) }
                    refresh()
                }

                else -> {
                    // Screen-only events were handled by edited().
                }
            }
        }

        private fun refresh() {
            viewModelScope.launch {
                val result = claims.claims()
                state.update { s ->
                    when (result) {
                        is AppResult.Success -> s.copy(loading = false, claims = result.value)

                        // Signed out or no server: the screen says so; the list just stays empty.
                        is AppResult.Failure -> s.copy(loading = false)
                    }
                }
            }
        }

        private fun start() {
            if (!state.value.canStart) return
            val form = formOf(state.value.fields, normalizer, homeRegion.homeRegion() ?: UNKNOWN_REGION)
            if (form == null) {
                state.update { it.copy(problem = BusinessProblem.InvalidNumber) }
            } else {
                perform({ claims.start(form) }) { s, claim -> s.copy(active = claim, step = ClaimStep.CODE, code = "") }
            }
        }

        private fun verify() {
            val id =
                state.value.active
                    ?.id
                    ?.takeIf { state.value.canVerify } ?: return
            val code = state.value.code
            perform({ claims.verify(id, code) }) { s, claim -> verified(s, claim) }
        }

        private fun resend(method: ClaimMethod) {
            val id = state.value.active?.id ?: return
            perform({ claims.resend(id, method) }) { s, claim -> s.copy(active = claim, code = "") }
        }

        private fun withdraw(id: String) =
            perform({ claims.withdraw(id) }) { s, _ ->
                s.copy(claims = s.claims.map { if (it.id == id) it.withdrawn() else it })
            }

        private fun upload(
            uri: String,
            kind: ClaimFileKind,
        ) {
            val id = state.value.active?.id ?: return
            perform({
                val picked = if (kind == ClaimFileKind.LOGO) files.logo(uri) else files.document(uri)
                picked.flatMap { file -> claims.upload(id, kind, file.file).map { key -> UploadedFile(file.name, key) } }
            }) { s, uploaded ->
                if (kind == ClaimFileKind.LOGO) s.copy(logo = uploaded) else s.copy(documents = s.documents + uploaded)
            }
        }

        private fun submit() {
            val current = state.value
            val id = current.active?.id?.takeIf { current.canSubmit } ?: return
            perform({ claims.submit(id, current.documents.map { it.key }, current.logo?.key) }) { s, claim ->
                s.copy(
                    step = ClaimStep.LIST,
                    active = null,
                    documents = emptyList(),
                    logo = null,
                    claims = listOf(claim) + s.claims.filterNot { it.id == claim.id },
                )
            }
        }

        // Runs one server step: busy while it runs, then [onSuccess] or the problem.
        private fun <T> perform(
            call: suspend () -> AppResult<T>,
            onSuccess: (ClaimUiState, T) -> ClaimUiState,
        ) {
            if (state.value.busy) return
            state.update { it.copy(busy = true, problem = null) }
            viewModelScope.launch {
                val result = call()
                state.update { s ->
                    when (result) {
                        is AppResult.Success -> onSuccess(s.copy(busy = false), result.value)
                        is AppResult.Failure -> s.copy(busy = false, problem = result.error.toBusinessProblem())
                    }
                }
            }
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val UNKNOWN_REGION = "ZZ"
        }
    }

// The form with the number in E.164 (typed in the national or international format), or null if it isn't one.
private fun formOf(
    fields: ClaimFields,
    normalizer: PhoneNumberNormalizer,
    region: String,
): ClaimForm? {
    val number = (normalizer.normalize(fields.number, region) as? AppResult.Success)?.value
    val category = fields.category
    return if (number == null || category == null) {
        null
    } else {
        ClaimForm(
            number = number,
            legalName = fields.legalName,
            displayName = fields.displayName,
            category = category,
            website = fields.website,
            hours = fields.hours,
            callReason = fields.callReason,
            method = fields.method,
        )
    }
}

// Events that change only the screen; null for the ones that need the server.
private fun edited(
    state: ClaimUiState,
    event: ClaimEvent,
): ClaimUiState? =
    when (event) {
        ClaimEvent.NewClaim -> state.copy(step = ClaimStep.FORM, fields = ClaimFields(), problem = null)
        is ClaimEvent.Continue -> continued(state, event.claim)
        is ClaimEvent.EditFields -> state.copy(fields = event.fields)
        is ClaimEvent.EditCode -> state.copy(code = digitsOf(event.code))
        is ClaimEvent.RemoveDocument -> state.copy(documents = state.documents.filterNot { it.key == event.key })
        ClaimEvent.DismissProblem -> state.copy(problem = null)
        else -> null
    }

// A claim waiting for its code opens the code step, a verified one the documents; others only show their status.
private fun continued(
    state: ClaimUiState,
    claim: BusinessClaim,
): ClaimUiState =
    when (claim.status) {
        ClaimStatus.VERIFYING -> {
            state.copy(step = ClaimStep.CODE, active = claim, code = "", problem = null)
        }

        ClaimStatus.VERIFIED -> {
            state.copy(step = ClaimStep.DOCUMENTS, active = claim, documents = emptyList(), logo = null, problem = null)
        }

        else -> {
            state
        }
    }

private fun verified(
    state: ClaimUiState,
    claim: BusinessClaim,
): ClaimUiState =
    if (claim.status == ClaimStatus.VERIFIED) {
        state.copy(active = claim, step = ClaimStep.DOCUMENTS, documents = emptyList(), logo = null)
    } else {
        state.copy(active = claim)
    }

private fun BusinessClaim.withdrawn() = copy(status = ClaimStatus.WITHDRAWN, codeExpiresInSeconds = null)

private fun digitsOf(code: String) = code.filter(Char::isDigit).take(ClaimUiState.CODE_LENGTH)
