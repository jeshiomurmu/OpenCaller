package io.github.jeshiomurmu.opencaller.feature.otp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme

/** The warning with its view model. */
@Composable
fun OtpGuardRoute(
    viewModel: OtpGuardViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OtpGuardScreen(
        state = state,
        onHangUp = {
            viewModel.onEvent(OtpGuardEvent.HangUp)
            onClose()
        },
        onClose = onClose,
        modifier = modifier,
    )
}

/** "Don't share this code": the danger colours, who sent it, Hang up (as the default phone app) and I understand. */
@Composable
fun OtpGuardScreen(
    state: OtpGuardUiState,
    onHangUp: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val danger = OpenCallerTheme.semanticColors
    Surface(color = danger.dangerContainer, contentColor = danger.onDangerContainer, modifier = modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m, Alignment.CenterVertically),
            modifier =
                Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(OpenCallerSpacing.l),
        ) {
            Icon(painterResource(OpenCallerIcons.Danger), contentDescription = null, modifier = Modifier.size(OpenCallerSpacing.iconLarge))
            Text(
                text = stringResource(R.string.otp_guard_title),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = stringResource(R.string.otp_guard_body), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            source(state)?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
            if (state.canHangUp) {
                Button(
                    onClick = onHangUp,
                    colors = ButtonDefaults.buttonColors(containerColor = danger.danger, contentColor = danger.onDanger),
                    modifier = Modifier.fillMaxWidth().heightIn(min = OpenCallerSpacing.minTouchTarget),
                ) { Text(stringResource(R.string.otp_guard_hang_up)) }
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = OpenCallerSpacing.minTouchTarget)) {
                Text(stringResource(R.string.otp_guard_close))
            }
        }
    }
}

@Composable
private fun source(state: OtpGuardUiState): String? =
    when {
        state.codeFrom == null -> null
        state.flashCall -> stringResource(R.string.otp_guard_flash_call, state.codeFrom)
        else -> stringResource(R.string.otp_guard_from, state.codeFrom)
    }

@OpenCallerPreviews
@Composable
private fun OtpGuardScreenPreview() {
    OpenCallerTheme { OtpGuardScreen(OtpGuardUiState(codeFrom = "Northpeak Bank", canHangUp = true), onHangUp = {}, onClose = {}) }
}

@OpenCallerPreviews
@Composable
private fun OtpGuardFlashCallPreview() {
    OpenCallerTheme { OtpGuardScreen(OtpGuardUiState(codeFrom = "Example Flash", flashCall = true), onHangUp = {}, onClose = {}) }
}
