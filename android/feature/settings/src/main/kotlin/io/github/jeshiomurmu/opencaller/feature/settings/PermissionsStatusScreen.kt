package io.github.jeshiomurmu.opencaller.feature.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.AccessItem
import io.github.jeshiomurmu.opencaller.core.model.AccessItemStatus
import io.github.jeshiomurmu.opencaller.core.model.AfterCallSurface
import io.github.jeshiomurmu.opencaller.core.model.CallerIdSurface
import io.github.jeshiomurmu.opencaller.core.telephony.AccessRequest
import io.github.jeshiomurmu.opencaller.core.ui.AccessItemExplainer
import io.github.jeshiomurmu.opencaller.core.ui.iconRes
import io.github.jeshiomurmu.opencaller.core.ui.labelRes
import io.github.jeshiomurmu.opencaller.core.ui.rememberAccessRequestLauncher

/**
 * Settings → Permissions & roles, wired to its view model. [explain] opens that item's explainer once, for
 * entry points such as the degraded-mode banner's Fix.
 */
@Composable
fun PermissionsStatusRoute(
    modifier: Modifier = Modifier,
    explain: AccessItem? = null,
    viewModel: PermissionsStatusViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val launcher = rememberAccessRequestLauncher { viewModel.onEvent(PermissionsStatusEvent.RequestResult(it)) }
    LaunchedEffect(explain) { viewModel.openOnce(explain) }
    LaunchedEffect(state.pendingRequest) {
        when (val request = state.pendingRequest) {
            is AccessRequest.RuntimePermission -> launcher.requestPermission(request.permission)
            is AccessRequest.SystemScreen -> launcher.openScreen(request.intent)
            null -> return@LaunchedEffect
        }
        viewModel.onEvent(PermissionsStatusEvent.RequestLaunched)
    }
    BackHandler(enabled = state.explaining != null) { viewModel.onEvent(PermissionsStatusEvent.ExplainerNotNow) }
    PermissionsStatusScreen(state = state, onEvent = viewModel::onEvent, modifier = modifier)
}

/** Stateless status screen: the explainer while one is open, otherwise the list. */
@Composable
fun PermissionsStatusScreen(
    state: PermissionsStatusUiState,
    onEvent: (PermissionsStatusEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val explaining = state.explaining
    if (explaining != null) {
        AccessItemExplainer(
            item = explaining,
            onContinue = { onEvent(PermissionsStatusEvent.ExplainerContinue) },
            onNotNow = { onEvent(PermissionsStatusEvent.ExplainerNotNow) },
            modifier = modifier,
            opensSettings = state.explainerOpensSettings,
        )
        return
    }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        Text(
            text = stringResource(R.string.permissions_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        state.rows.forEach { AccessRowItem(row = it, onFix = { onEvent(PermissionsStatusEvent.Fix(it.item)) }) }
        if (state.settingsPageMissing) {
            Text(text = stringResource(R.string.permissions_settings_missing), style = MaterialTheme.typography.bodyMedium)
        }
        HowItWorks(callerId = state.callerIdSurface, afterCall = state.afterCallSurface)
    }
}

@Composable
private fun AccessRowItem(
    row: AccessRow,
    onFix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), shape = OpenCallerShape.card, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.padding(OpenCallerSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
        ) {
            Icon(painter = painterResource(row.item.iconRes()), contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(row.item.labelRes()), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(statusText(row.status)),
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (row.status ==
                            AccessItemStatus.GRANTED
                        ) {
                            OpenCallerTheme.semanticColors.safe
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            if (row.status == AccessItemStatus.NOT_GRANTED) {
                OutlinedButton(onClick = onFix) { Text(text = stringResource(R.string.permissions_fix)) }
            }
        }
    }
}

@Composable
private fun HowItWorks(
    callerId: CallerIdSurface,
    afterCall: AfterCallSurface,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(top = OpenCallerSpacing.s), verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xxs)) {
        Text(text = stringResource(R.string.permissions_how_title), style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(callerIdText(callerId)), style = MaterialTheme.typography.bodyMedium)
        Text(text = stringResource(afterCallText(afterCall)), style = MaterialTheme.typography.bodyMedium)
    }
}

@StringRes
private fun statusText(status: AccessItemStatus): Int =
    when (status) {
        AccessItemStatus.GRANTED -> R.string.permissions_status_on
        AccessItemStatus.NOT_GRANTED -> R.string.permissions_status_off
        AccessItemStatus.COMING_LATER -> R.string.permissions_status_coming_later
        AccessItemStatus.NOT_APPLICABLE -> R.string.permissions_status_not_needed
    }

@StringRes
private fun callerIdText(surface: CallerIdSurface): Int =
    when (surface) {
        CallerIdSurface.IN_CALL_SCREEN -> R.string.permissions_caller_id_in_call
        CallerIdSurface.OVERLAY -> R.string.permissions_caller_id_overlay
        CallerIdSurface.NOTIFICATION -> R.string.permissions_caller_id_notification
    }

@StringRes
private fun afterCallText(surface: AfterCallSurface): Int =
    when (surface) {
        AfterCallSurface.NOTIFICATION -> R.string.permissions_after_call_notification
        AfterCallSurface.IN_APP_HISTORY -> R.string.permissions_after_call_history
    }

@OpenCallerPreviews
@Composable
private fun PermissionsStatusPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            PermissionsStatusScreen(
                state =
                    PermissionsStatusUiState(
                        rows =
                            listOf(
                                AccessRow(AccessItem.CALL_SCREENING, AccessItemStatus.NOT_GRANTED),
                                AccessRow(AccessItem.CONTACTS, AccessItemStatus.GRANTED),
                                AccessRow(AccessItem.NOTIFICATIONS, AccessItemStatus.NOT_GRANTED),
                                AccessRow(AccessItem.DEFAULT_PHONE_APP, AccessItemStatus.COMING_LATER),
                                AccessRow(AccessItem.OVERLAY, AccessItemStatus.NOT_GRANTED),
                            ),
                        afterCallSurface = AfterCallSurface.IN_APP_HISTORY,
                    ),
                onEvent = {},
            )
        }
    }
}
