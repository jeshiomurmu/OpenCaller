package io.github.jeshiomurmu.opencaller.feature.premium

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.account.AccountStatus
import io.github.jeshiomurmu.opencaller.core.data.supporter.EntitlementProvider
import io.github.jeshiomurmu.opencaller.core.data.supporter.PurchaseOutcome
import io.github.jeshiomurmu.opencaller.core.data.supporter.SupporterOffer
import io.github.jeshiomurmu.opencaller.core.data.supporter.SupporterStore
import io.github.jeshiomurmu.opencaller.core.model.Entitlement
import io.github.jeshiomurmu.opencaller.core.model.SupporterPlan
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What went wrong on the supporter screen. */
sealed interface SupporterProblem {
    /** No connection. */
    data object Offline : SupporterProblem

    /** This build has no server. */
    data object NotConnected : SupporterProblem

    /** Sign in first (a purchase belongs to an account). */
    data object SignInFirst : SupporterProblem

    /** The store has no plans for this app (not set up in Play Console, or Play unavailable). */
    data object NoPlans : SupporterProblem

    /** The server refused the purchase (another account's, or not the supporter subscription). */
    data object NotAccepted : SupporterProblem

    /** Anything else. */
    data object Unexpected : SupporterProblem
}

/** Short notes after an action. */
enum class SupporterNote {
    /** Thanks: the purchase is verified. */
    THANKS,

    /** The payment isn't complete yet. */
    PENDING,

    /** Nothing to restore. */
    NOTHING_TO_RESTORE,
}

/**
 * The supporter screen (docs/prd.md F-UT-08).
 *
 * @property storeAvailable the build can buy in a store (play).
 * @property donationUrl the foss build's donation link.
 */
data class SupporterUiState(
    val entitlement: Entitlement = Entitlement.NONE,
    val offer: SupporterOffer = SupporterOffer(),
    val signedIn: Boolean = false,
    val storeAvailable: Boolean = false,
    val donationUrl: String? = null,
    val plans: List<SupporterPlan> = emptyList(),
    val busy: Boolean = false,
    val problem: SupporterProblem? = null,
    val note: SupporterNote? = null,
)

/** Supporter status, plans, purchase and restore. A purchase counts only once the server has verified it. */
@HiltViewModel
class SupporterViewModel
    @Inject
    constructor(
        private val entitlements: EntitlementProvider,
        private val store: SupporterStore,
        private val account: AccountStatus,
    ) : ViewModel() {
        private val local = MutableStateFlow(SupporterUiState(storeAvailable = store.available, donationUrl = store.donationUrl))

        /** Screen state. */
        val uiState: StateFlow<SupporterUiState> =
            combine(local, entitlements.entitlement, entitlements.offer, account.signedIn) { state, entitlement, offer, signedIn ->
                state.copy(entitlement = entitlement, offer = offer, signedIn = signedIn)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), local.value)

        /** Asks the server again and loads the plans (on open, and "Restore purchases"). */
        fun refresh(restoring: Boolean = false) {
            viewModelScope.launch {
                local.update { it.copy(busy = true, problem = null, note = null) }
                val result = entitlements.refresh()
                val problem = (result as? AppResult.Failure)?.error?.toProblem()
                val note =
                    if (restoring && result is AppResult.Success &&
                        !result.value.entitled
                    ) {
                        SupporterNote.NOTHING_TO_RESTORE
                    } else {
                        null
                    }
                local.update { it.copy(busy = false, problem = problem, note = note) }
                loadPlans()
            }
        }

        /** Buys [plan] in the store's dialog over [activity], then has the server verify the purchase. */
        fun buy(
            activity: Activity,
            plan: SupporterPlan,
        ) {
            viewModelScope.launch {
                val offer = entitlements.offer.first()
                val productId = offer.productId
                val accountId = offer.billingAccountId
                if (productId == null || accountId == null || !account.signedIn.first()) {
                    local.update { it.copy(problem = SupporterProblem.SignInFirst) }
                    return@launch
                }
                local.update { it.copy(busy = true, problem = null, note = null) }
                val outcome = store.buy(activity, productId, plan, accountId)
                local.update { it.copy(busy = false) }
                handle(productId, outcome)
            }
        }

        private suspend fun handle(
            productId: String,
            outcome: AppResult<PurchaseOutcome>,
        ) {
            when (outcome) {
                is AppResult.Failure -> {
                    local.update { it.copy(problem = outcome.error.toProblem()) }
                }

                is AppResult.Success -> {
                    when (val value = outcome.value) {
                        PurchaseOutcome.Cancelled -> {}

                        // the user closed the dialog; nothing to say
                        PurchaseOutcome.Pending -> {
                            local.update { it.copy(note = SupporterNote.PENDING) }
                        }

                        is PurchaseOutcome.Purchased -> {
                            verify(productId, value.purchaseToken)
                        }
                    }
                }
            }
        }

        private suspend fun verify(
            productId: String,
            token: String,
        ) {
            local.update { it.copy(busy = true) }
            when (val verified = entitlements.verifyPurchase(productId, token)) {
                is AppResult.Success -> local.update { it.copy(busy = false, note = SupporterNote.THANKS) }
                is AppResult.Failure -> local.update { it.copy(busy = false, problem = verified.error.toProblem()) }
            }
        }

        // Plans come from the store, for the product the server verifies; a foss build or a server without Play
        // billing has none.
        private suspend fun loadPlans() {
            val productId = entitlements.offer.first().productId
            if (!store.available || productId == null) {
                local.update { it.copy(plans = emptyList()) }
                return
            }
            when (val plans = store.plans(productId)) {
                is AppResult.Success -> {
                    local.update {
                        it.copy(plans = plans.value, problem = if (plans.value.isEmpty()) SupporterProblem.NoPlans else it.problem)
                    }
                }

                is AppResult.Failure -> {
                    local.update { it.copy(plans = emptyList(), problem = it.problem ?: SupporterProblem.NoPlans) }
                }
            }
        }

        private fun AppError.toProblem(): SupporterProblem =
            when (this) {
                AppError.Network -> SupporterProblem.Offline
                AppError.ServerNotConfigured -> SupporterProblem.NotConnected
                AppError.Auth -> SupporterProblem.SignInFirst
                is AppError.Validation, AppError.NotFound -> SupporterProblem.NotAccepted
                else -> SupporterProblem.Unexpected
            }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
