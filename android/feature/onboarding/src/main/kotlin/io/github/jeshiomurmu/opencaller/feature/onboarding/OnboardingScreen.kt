package io.github.jeshiomurmu.opencaller.feature.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.AccessItem
import io.github.jeshiomurmu.opencaller.core.model.AccessItemStatus
import io.github.jeshiomurmu.opencaller.core.model.OnboardingStep
import io.github.jeshiomurmu.opencaller.core.telephony.AccessRequest
import io.github.jeshiomurmu.opencaller.core.ui.AccessItemExplainer
import io.github.jeshiomurmu.opencaller.core.ui.rememberAccessRequestLauncher

/** Onboarding, wired to its [OnboardingViewModel]. The app shows its home screen once the step is DONE. */
@Composable
fun OnboardingRoute(
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val launcher = rememberAccessRequestLauncher { viewModel.onEvent(OnboardingEvent.RequestResult(it)) }
    LaunchedEffect(state.pendingRequest) {
        when (val request = state.pendingRequest) {
            is AccessRequest.RuntimePermission -> launcher.requestPermission(request.permission)
            is AccessRequest.SystemScreen -> launcher.openScreen(request.intent)
            null -> return@LaunchedEffect
        }
        viewModel.onEvent(OnboardingEvent.RequestLaunched)
    }
    OnboardingScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

/** Stateless onboarding screen. */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onEvent: (OnboardingEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.safeDrawingPadding()) {
            val progress = stringResource(R.string.onboarding_progress, state.stepNumber, state.stepCount)
            when (val page = state.page) {
                OnboardingPage.Loading, OnboardingPage.Finished -> {
                    Unit
                }

                OnboardingPage.Welcome -> {
                    WelcomePage(progress = progress, onNext = { onEvent(OnboardingEvent.Next) })
                }

                OnboardingPage.Privacy -> {
                    PrivacyPage(progress = progress, onNext = { onEvent(OnboardingEvent.Next) })
                }

                is OnboardingPage.Explainer -> {
                    AccessItemExplainer(
                        item = page.item,
                        onContinue = { onEvent(OnboardingEvent.ExplainerContinue) },
                        onNotNow = { onEvent(OnboardingEvent.ExplainerNotNow) },
                        progress = progress,
                        opensSettings = page.opensSettings,
                    )
                }

                is OnboardingPage.Extras -> {
                    ExtrasPage(
                        options = page.options,
                        progress = progress,
                        onOpen = { onEvent(OnboardingEvent.OpenExtra(it)) },
                        onFinish = { onEvent(OnboardingEvent.Finish) },
                    )
                }
            }
        }
    }
}

@OpenCallerPreviews
@Composable
private fun OnboardingWelcomePreview() {
    OpenCallerTheme {
        OnboardingScreen(state = OnboardingUiState(page = OnboardingPage.Welcome), onEvent = {})
    }
}

@OpenCallerPreviews
@Composable
private fun OnboardingPrivacyPreview() {
    OpenCallerTheme {
        OnboardingScreen(state = OnboardingUiState(step = OnboardingStep.PRIVACY, page = OnboardingPage.Privacy), onEvent = {})
    }
}

@OpenCallerPreviews
@Composable
private fun OnboardingExtrasPreview() {
    OpenCallerTheme {
        OnboardingScreen(
            state =
                OnboardingUiState(
                    step = OnboardingStep.EXTRAS,
                    page =
                        OnboardingPage.Extras(
                            listOf(
                                ExtraOption(AccessItem.DEFAULT_PHONE_APP, AccessItemStatus.COMING_LATER),
                                ExtraOption(AccessItem.DEFAULT_SMS_APP, AccessItemStatus.COMING_LATER),
                                ExtraOption(AccessItem.OVERLAY, AccessItemStatus.NOT_GRANTED),
                            ),
                        ),
                ),
            onEvent = {},
        )
    }
}
