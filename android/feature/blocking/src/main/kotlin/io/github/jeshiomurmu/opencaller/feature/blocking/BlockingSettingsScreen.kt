package io.github.jeshiomurmu.opencaller.feature.blocking

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.SpamThreshold
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import io.github.jeshiomurmu.opencaller.core.ui.WarningBanner
import java.util.Locale

/**
 * The Protection tab, wired to its view model. [reportNumber] (E.164) opens the report dialog, e.g. from the
 * after-call notification's Report.
 */
@Composable
fun BlockingRoute(
    onOpenRules: () -> Unit,
    onOpenBlockedLog: () -> Unit,
    onFixContacts: () -> Unit,
    modifier: Modifier = Modifier,
    reportNumber: String? = null,
    viewModel: BlockingSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(reportNumber) { reportNumber?.let(viewModel::showReport) }
    LifecycleResumeEffect(Unit) {
        viewModel.onEvent(BlockingEvent.Resumed)
        onPauseOrDispose {}
    }
    val snackbar = remember { SnackbarHostState() }
    val message = state.message?.let { messageText(it) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.onEvent(BlockingEvent.MessageShown)
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        BlockingScreen(
            state = state,
            onEvent = viewModel::onEvent,
            onOpenRules = onOpenRules,
            onOpenBlockedLog = onOpenBlockedLog,
            onFixContacts = onFixContacts,
        )
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
    state.report?.let { ReportDialog(report = it, onEvent = viewModel::onEvent) }
}

@Composable
private fun messageText(message: BlockingMessage): String =
    when (message) {
        is BlockingMessage.Reported -> {
            stringResource(
                if (message.blocked) R.string.report_done_blocked else R.string.report_done,
                message.masked,
            )
        }

        BlockingMessage.Failed -> {
            stringResource(R.string.blocking_failed)
        }
    }

/** Blocking settings: toggles, the spam threshold, allowed countries and links to the rules and the blocked log. */
@Composable
fun BlockingScreen(
    state: BlockingUiState,
    onEvent: (BlockingEvent) -> Unit,
    onOpenRules: () -> Unit,
    onOpenBlockedLog: () -> Unit,
    onFixContacts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = OpenCallerSpacing.m)) {
        Title(stringResource(R.string.blocking_title))
        if (state.contactsMissing) {
            WarningBanner(
                message = stringResource(R.string.blocking_contacts_missing),
                actionLabel = stringResource(R.string.blocking_fix),
                onAction = onFixContacts,
            )
        }
        LinkRow(stringResource(R.string.blocking_rules), stringResource(R.string.blocking_rules_summary), onOpenRules)
        LinkRow(stringResource(R.string.blocking_log), stringResource(R.string.blocking_log_summary), onOpenBlockedLog)
        CallToggles(prefs = state.preferences, homeRegion = state.homeRegion, onEvent = onEvent)
        ThresholdPicker(selected = state.preferences.spamThreshold, onEvent = onEvent)
        AllowedCountries(codes = state.preferences.allowedRegionCodes.sorted(), error = state.countryError, onEvent = onEvent)
    }
}

@Composable
private fun CallToggles(
    prefs: UserPreferences,
    homeRegion: String?,
    onEvent: (BlockingEvent) -> Unit,
) {
    Section(stringResource(R.string.blocking_section_calls))
    ToggleRow(stringResource(R.string.blocking_hidden), stringResource(R.string.blocking_hidden_summary), prefs.blockHiddenNumbers) { on ->
        onEvent(BlockingEvent.Update { it.copy(blockHiddenNumbers = on) })
    }
    val foreignSummary =
        homeRegion?.let { stringResource(R.string.blocking_foreign_summary, countryName(it)) }
            ?: stringResource(R.string.blocking_foreign_summary_unknown)
    ToggleRow(stringResource(R.string.blocking_foreign), foreignSummary, prefs.blockForeignNumbers) { on ->
        onEvent(BlockingEvent.Update { it.copy(blockForeignNumbers = on) })
    }
    ToggleRow(
        stringResource(R.string.blocking_non_contacts),
        stringResource(R.string.blocking_non_contacts_summary),
        prefs.blockNonContacts,
    ) { on ->
        onEvent(BlockingEvent.Update { it.copy(blockNonContacts = on) })
    }
    ToggleRow(
        stringResource(R.string.blocking_top_spammers),
        stringResource(R.string.blocking_top_spammers_summary),
        prefs.blockTopSpammers,
    ) { on ->
        onEvent(BlockingEvent.Update { it.copy(blockTopSpammers = on) })
    }
}

@Composable
private fun ThresholdPicker(
    selected: SpamThreshold,
    onEvent: (BlockingEvent) -> Unit,
) {
    Section(stringResource(R.string.blocking_section_threshold))
    BlockingSettingsViewModel.THRESHOLDS.forEach { threshold ->
        val (title, summary) = threshold.labels()
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = threshold == selected, role = Role.RadioButton) {
                        onEvent(BlockingEvent.Update { it.copy(spamThreshold = threshold) })
                    }.padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
        ) {
            RadioButton(selected = threshold == selected, onClick = null)
            TextPair(title = stringResource(title), summary = stringResource(summary), modifier = Modifier.weight(1f))
        }
    }
}

private fun SpamThreshold.labels(): Pair<Int, Int> =
    when (this) {
        SpamThreshold.LOW -> R.string.threshold_low to R.string.threshold_low_summary
        SpamThreshold.MEDIUM -> R.string.threshold_medium to R.string.threshold_medium_summary
        SpamThreshold.HIGH -> R.string.threshold_high to R.string.threshold_high_summary
    }

@OpenCallerPreviews
@Composable
private fun BlockingScreenPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            BlockingScreen(
                state =
                    BlockingUiState(
                        loading = false,
                        preferences = UserPreferences(blockNonContacts = true, allowedRegionCodes = setOf("US", "GB")),
                        contactsMissing = true,
                        homeRegion = "BD",
                    ),
                onEvent = {},
                onOpenRules = {},
                onOpenBlockedLog = {},
                onFixContacts = {},
            )
        }
    }
}
