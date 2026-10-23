package io.github.jeshiomurmu.opencaller.feature.dialer

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.ui.DialPad
import io.github.jeshiomurmu.opencaller.core.ui.PermissionExplainer

/** Alpha of the Call button while there is no number. */
private const val DISABLED_ALPHA = 0.38f

/**
 * The dial pad, wired to its view model. [initialNumber] fills it once (a `tel:` link). The call permission is asked
 * for after its explainer, and only when OpenCaller is the default phone app.
 */
@Composable
fun DialPadRoute(
    initialNumber: String?,
    modifier: Modifier = Modifier,
    viewModel: DialPadViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val askToCall =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.onEvent(DialPadEvent.PermissionResult(granted))
        }
    LaunchedEffect(initialNumber) { viewModel.onEvent(DialPadEvent.Prefill(initialNumber)) }
    if (state.explainingPermission) {
        PermissionExplainer(
            icon = OpenCallerIcons.Calls,
            title = stringResource(R.string.dialpad_permission_title),
            why = stringResource(R.string.dialpad_permission_why),
            never = stringResource(R.string.dialpad_permission_never),
            onContinue = { askToCall.launch(Manifest.permission.CALL_PHONE) },
            onNotNow = { viewModel.onEvent(DialPadEvent.CancelPermission) },
            modifier = modifier,
        )
    } else {
        DialPadScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
    }
}

/** Number entry, the keypad and Call. */
@Composable
fun DialPadScreen(
    state: DialPadUiState,
    onEvent: (DialPadEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = OpenCallerSpacing.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
    ) {
        Spacer(modifier = Modifier.weight(1f))
        NumberRow(number = state.number, onDelete = { onEvent(DialPadEvent.Delete) })
        val message =
            when (state.problem) {
                DialProblem.NO_PERMISSION -> stringResource(R.string.dialpad_permission_denied)
                DialProblem.CANT_CALL -> stringResource(R.string.dialpad_cant_call)
                null -> if (state.isDefaultPhoneApp) null else stringResource(R.string.dialpad_hand_off_note)
            }
        message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        DialPad(onKeyDown = { onEvent(DialPadEvent.Key(it)) }, onKeyUp = {})
        CallButton(enabled = state.number.isNotBlank(), onClick = { onEvent(DialPadEvent.Call) })
    }
}

@Composable
private fun NumberRow(
    number: String,
    onDelete: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Spacer(modifier = Modifier.size(OpenCallerSpacing.minTouchTarget))
        Text(
            text = number.ifEmpty { stringResource(R.string.dialpad_hint) },
            style = OpenCallerTheme.extendedTypography.dialerNumber,
            color = if (number.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (number.isEmpty()) {
            Spacer(modifier = Modifier.size(OpenCallerSpacing.minTouchTarget))
        } else {
            IconButton(onClick = onDelete) {
                Icon(painter = painterResource(OpenCallerIcons.Backspace), contentDescription = stringResource(R.string.dialpad_delete))
            }
        }
    }
}

@Composable
private fun CallButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
        modifier =
            Modifier
                .padding(bottom = OpenCallerSpacing.l)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .alpha(if (enabled) 1f else DISABLED_ALPHA),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(OpenCallerSpacing.callAction).background(MaterialTheme.colorScheme.primary, CircleShape),
        ) {
            Icon(painter = painterResource(OpenCallerIcons.Calls), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        Text(text = stringResource(R.string.dialpad_call), style = MaterialTheme.typography.labelLarge)
    }
}

@OpenCallerPreviews
@Composable
private fun DialPadScreenPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            DialPadScreen(state = DialPadUiState(number = "+1 201 555 0123", isDefaultPhoneApp = true), onEvent = {})
        }
    }
}

@OpenCallerPreviews
@Composable
private fun DialPadNotDefaultPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            DialPadScreen(state = DialPadUiState(), onEvent = {})
        }
    }
}
