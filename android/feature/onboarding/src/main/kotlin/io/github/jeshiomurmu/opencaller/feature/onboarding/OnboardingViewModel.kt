package io.github.jeshiomurmu.opencaller.feature.onboarding

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.model.AccessItem
import io.github.jeshiomurmu.opencaller.core.model.AccessItemStatus
import io.github.jeshiomurmu.opencaller.core.model.AccessRequestOutcome
import io.github.jeshiomurmu.opencaller.core.model.AccessStatus
import io.github.jeshiomurmu.opencaller.core.model.OnboardingStep
import io.github.jeshiomurmu.opencaller.core.telephony.AccessRequest
import io.github.jeshiomurmu.opencaller.core.telephony.AccessRequestFactory
import io.github.jeshiomurmu.opencaller.core.telephony.AccessStatusSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the onboarding screen shows. */
sealed interface OnboardingPage {
    /** Settings are still loading. */
    data object Loading : OnboardingPage

    /** Step 1. */
    data object Welcome : OnboardingPage

    /** Step 2: what leaves the phone and what never does. */
    data object Privacy : OnboardingPage

    /** A [PermissionExplainer][io.github.jeshiomurmu.opencaller.core.ui.PermissionExplainer] for [item]. */
    data class Explainer(
        val item: AccessItem,
        val opensSettings: Boolean,
    ) : OnboardingPage

    /** Step 5: optional roles and overlay, each with its status. */
    data class Extras(
        val options: List<ExtraOption>,
    ) : OnboardingPage

    /** Onboarding is done; the app shows its home screen. */
    data object Finished : OnboardingPage
}

/** One optional item on the Extras step. */
data class ExtraOption(
    val item: AccessItem,
    val status: AccessItemStatus,
)

/**
 * Onboarding screen state.
 *
 * @property pendingRequest a request the screen must start now (then report [OnboardingEvent.RequestLaunched]).
 *   It is only ever set from the Continue action of an explainer page.
 */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val page: OnboardingPage = OnboardingPage.Loading,
    val pendingRequest: AccessRequest? = null,
) {
    /** "Step N" of [stepCount]. */
    val stepNumber: Int get() = OnboardingFlow.visibleNumber(step)

    /** Number of numbered steps. */
    val stepCount: Int get() = OnboardingFlow.VISIBLE_STEPS
}

/** User actions on the onboarding screen. */
sealed interface OnboardingEvent {
    /** Continue on Welcome or Privacy. */
    data object Next : OnboardingEvent

    /** Continue on an explainer: ask the system. */
    data object ExplainerContinue : OnboardingEvent

    /** "Not now" on an explainer. */
    data object ExplainerNotNow : OnboardingEvent

    /** "Set up" on an optional item. */
    data class OpenExtra(
        val item: AccessItem,
    ) : OnboardingEvent

    /** Finish on the Extras step. */
    data object Finish : OnboardingEvent

    /** The screen started [OnboardingUiState.pendingRequest]. */
    data object RequestLaunched : OnboardingEvent

    /** The system dialog or settings page returned. */
    data class RequestResult(
        val outcome: AccessRequestOutcome,
    ) : OnboardingEvent
}

/**
 * Drives onboarding: Welcome → Privacy → Call screening → Contacts → Notifications → Extras → Done, one
 * request at a time and each behind its explainer. The step is saved in user settings after every change
 * and the open optional item in [SavedStateHandle], so rotation and process death resume at the same place.
 */
@HiltViewModel
class OnboardingViewModel
    @Inject
    constructor(
        private val settings: UserSettingsDataSource,
        private val access: AccessStatusSource,
        private val requests: AccessRequestFactory,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        private val pending = MutableStateFlow<AccessRequest?>(null)

        /** Screen state. */
        val uiState: StateFlow<OnboardingUiState> =
            combine(
                settings.preferences,
                access.status,
                savedState.getStateFlow<String?>(KEY_OPEN_EXTRA, null),
                pending,
            ) { preferences, status, openExtra, request ->
                OnboardingUiState(
                    step = preferences.onboardingStep,
                    page = pageFor(preferences.onboardingStep, status, openExtra?.let(AccessItem::valueOf)),
                    pendingRequest = request,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), OnboardingUiState())

        /** Handles [event]. */
        fun onEvent(event: OnboardingEvent) {
            viewModelScope.launch {
                val current = uiState.first { it.page != OnboardingPage.Loading }
                when (event) {
                    OnboardingEvent.Next -> {
                        advanceFrom(current.step)
                    }

                    OnboardingEvent.ExplainerContinue -> {
                        askFor(current)
                    }

                    OnboardingEvent.ExplainerNotNow -> {
                        closeExplainer(current.step)
                    }

                    is OnboardingEvent.OpenExtra -> {
                        openExtra(event.item)
                    }

                    OnboardingEvent.Finish -> {
                        setStep(OnboardingStep.DONE)
                    }

                    OnboardingEvent.RequestLaunched -> {
                        pending.value = null
                    }

                    is OnboardingEvent.RequestResult -> {
                        access.refresh()
                        closeExplainer(current.step)
                    }
                }
            }
        }

        private fun pageFor(
            step: OnboardingStep,
            status: AccessStatus,
            openExtra: AccessItem?,
        ): OnboardingPage =
            when (step) {
                OnboardingStep.WELCOME -> {
                    OnboardingPage.Welcome
                }

                OnboardingStep.PRIVACY -> {
                    OnboardingPage.Privacy
                }

                OnboardingStep.CALL_SCREENING, OnboardingStep.CONTACTS, OnboardingStep.NOTIFICATIONS -> {
                    explainer(requireNotNull(OnboardingFlow.itemOf(step)), status)
                }

                OnboardingStep.EXTRAS -> {
                    openExtra?.let { explainer(it, status) }
                        ?: OnboardingPage.Extras(
                            OnboardingFlow.EXTRAS
                                .map { ExtraOption(it, status.statusOf(it)) }
                                .filter { it.status != AccessItemStatus.NOT_APPLICABLE },
                        )
                }

                OnboardingStep.DONE -> {
                    OnboardingPage.Finished
                }
            }

        private fun explainer(
            item: AccessItem,
            status: AccessStatus,
        ): OnboardingPage.Explainer {
            val request = requests.requestFor(item, status.roles)
            val isRole = item in setOf(AccessItem.CALL_SCREENING, AccessItem.DEFAULT_PHONE_APP, AccessItem.DEFAULT_SMS_APP)
            return OnboardingPage.Explainer(item, opensSettings = request is AccessRequest.SystemScreen && !isRole)
        }

        // The only place a request is created: the user pressed Continue on an explainer.
        private fun askFor(current: OnboardingUiState) {
            val page = current.page as? OnboardingPage.Explainer ?: return
            val request = requests.requestFor(page.item, access.status.value.roles)
            if (request == null) {
                closeExplainer(current.step)
            } else {
                pending.value = request
            }
        }

        private fun openExtra(item: AccessItem) {
            if (!access.status.value.isSettled(item)) savedState[KEY_OPEN_EXTRA] = item.name
        }

        private fun closeExplainer(step: OnboardingStep) {
            if (step == OnboardingStep.EXTRAS) {
                savedState[KEY_OPEN_EXTRA] = null
            } else {
                advanceFrom(step)
            }
        }

        private fun advanceFrom(step: OnboardingStep) {
            viewModelScope.launch {
                // Only advance from the step the event was sent on, so a double tap can't skip a step.
                settings.update {
                    if (it.onboardingStep == step) it.copy(onboardingStep = OnboardingFlow.next(step, access.status.value)) else it
                }
            }
        }

        private fun setStep(step: OnboardingStep) {
            viewModelScope.launch { settings.update { it.copy(onboardingStep = step) } }
        }

        private companion object {
            const val KEY_OPEN_EXTRA = "onboarding.openExtra"
            const val STOP_TIMEOUT_MILLIS = 5_000L
        }
    }
