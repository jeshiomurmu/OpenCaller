package io.github.jeshiomurmu.opencaller.feature.incall.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.ScamCue
import io.github.jeshiomurmu.opencaller.core.model.VoiceScanResult
import io.github.jeshiomurmu.opencaller.core.model.VoiceVerdict
import io.github.jeshiomurmu.opencaller.core.telephony.incall.RecordingFailure
import io.github.jeshiomurmu.opencaller.core.telephony.incall.VoiceScanStatus
import io.github.jeshiomurmu.opencaller.core.ui.CallControlButton
import io.github.jeshiomurmu.opencaller.feature.incall.InCallEvent
import io.github.jeshiomurmu.opencaller.feature.incall.InCallUiState
import io.github.jeshiomurmu.opencaller.feature.incall.R

/**
 * Scan voice (or Stop scan while listening). Scan first explains what happens (docs/prd.md F-PR-13, ADR 0011): both
 * parties hear a beep, the user's microphone is muted for a few seconds, nothing is recorded.
 */
@Composable
internal fun ScanControl(
    state: InCallUiState,
    onEvent: (InCallEvent) -> Unit,
) {
    var explain by rememberSaveable { mutableStateOf(false) }
    val listening = state.scan == VoiceScanStatus.Listening
    CallControlButton(
        icon = OpenCallerIcons.VoiceScan,
        label = stringResource(if (listening) R.string.incall_scan_stop else R.string.incall_scan),
        checked = listening,
        enabled = listening || state.canScan,
        onClick = { if (listening) onEvent(InCallEvent.StopScan) else explain = true },
    )
    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false },
            title = { Text(stringResource(R.string.incall_scan_explain_title)) },
            text = { Text(stringResource(R.string.incall_scan_explain_text)) },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    onEvent(InCallEvent.ScanVoice)
                }) { Text(stringResource(R.string.incall_scan_confirm)) }
            },
            dismissButton = { TextButton(onClick = { explain = false }) { Text(stringResource(R.string.incall_record_cancel)) } },
        )
    }
}

/** What the voice scan is doing, its result (with why it can be wrong) or why it didn't work; nothing while idle. */
@Composable
internal fun ScanPanel(
    status: VoiceScanStatus,
    onEvent: (InCallEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (status) {
        VoiceScanStatus.Idle -> Unit
        VoiceScanStatus.Connecting -> ScanNote(R.string.incall_scan_connecting, modifier = modifier)
        VoiceScanStatus.Merging -> ScanNote(R.string.incall_scan_merging, modifier = modifier)
        VoiceScanStatus.Listening -> ScanNote(R.string.incall_scan_listening, modifier = modifier)
        VoiceScanStatus.NoResult -> ScanNote(R.string.incall_scan_no_result, onClose = { onEvent(InCallEvent.ScanDismissed) }, modifier)
        is VoiceScanStatus.Failed -> ScanNote(messageOf(status.reason), onClose = { onEvent(InCallEvent.ScanDismissed) }, modifier)
        is VoiceScanStatus.Done -> ScanResultCard(status.result, onClose = { onEvent(InCallEvent.ScanDismissed) }, modifier)
    }
}

@Composable
private fun ScanNote(
    @StringRes text: Int,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = OpenCallerShape.card,
        color = if (onClose != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(OpenCallerSpacing.s), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            onClose?.let { TextButton(onClick = it) { Text(stringResource(R.string.incall_recording_ok)) } }
        }
    }
}

/** "Likely human" or "Possibly AI-generated", what that means, that it can be wrong, and the scam cues heard. */
@Composable
internal fun ScanResultCard(
    result: VoiceScanResult,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ai = result.verdict == VoiceVerdict.POSSIBLY_AI
    val warning = ai || result.cues.isNotEmpty()
    Surface(
        shape = OpenCallerShape.card,
        color = if (warning) OpenCallerTheme.semanticColors.warningContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (warning) OpenCallerTheme.semanticColors.warning else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(OpenCallerSpacing.m), verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
            Text(
                stringResource(if (ai) R.string.incall_scan_possibly_ai else R.string.incall_scan_likely_human),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(if (ai) R.string.incall_scan_possibly_ai_text else R.string.incall_scan_likely_human_text),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (result.cues.isEmpty()) {
                Text(stringResource(R.string.incall_scan_no_cues), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(stringResource(R.string.incall_scan_cues_title), style = MaterialTheme.typography.labelLarge)
                result.cues.forEach { cue -> Text("• " + stringResource(cueText(cue)), style = MaterialTheme.typography.bodyMedium) }
            }
            Text(stringResource(R.string.incall_scan_can_be_wrong), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.incall_scan_close)) }
        }
    }
}

@StringRes
private fun messageOf(reason: RecordingFailure): Int =
    when (reason) {
        RecordingFailure.UNAVAILABLE -> R.string.incall_scan_unavailable
        RecordingFailure.SUPPORTER_REQUIRED -> R.string.incall_scan_supporter
        RecordingFailure.NOT_SIGNED_IN -> R.string.incall_scan_signed_out
        RecordingFailure.NETWORK -> R.string.incall_recording_network
        RecordingFailure.CANNOT_DIAL, RecordingFailure.LINE_NO_ANSWER, RecordingFailure.MERGE_FAILED -> R.string.incall_scan_failed
    }

/** The phrase shown after "They mentioned:" for [cue]. */
@StringRes
internal fun cueText(cue: ScamCue): Int =
    when (cue) {
        ScamCue.OTP_REQUEST -> R.string.incall_scan_cue_otp_request
        ScamCue.PAYMENT_REQUEST -> R.string.incall_scan_cue_payment_request
        ScamCue.GIFT_CARD -> R.string.incall_scan_cue_gift_card
        ScamCue.REMOTE_ACCESS -> R.string.incall_scan_cue_remote_access
        ScamCue.URGENCY -> R.string.incall_scan_cue_urgency
        ScamCue.AUTHORITY -> R.string.incall_scan_cue_authority
        ScamCue.SECRECY -> R.string.incall_scan_cue_secrecy
        ScamCue.PRIZE -> R.string.incall_scan_cue_prize
        ScamCue.ACCOUNT_PROBLEM -> R.string.incall_scan_cue_account_problem
        ScamCue.PERSONAL_DATA -> R.string.incall_scan_cue_personal_data
    }

private const val PREVIEW_AI_SCORE = 0.91f
private const val PREVIEW_HUMAN_SCORE = 0.12f

@OpenCallerPreviews
@Composable
private fun ScanResultPossiblyAiPreview() {
    OpenCallerTheme {
        ScanResultCard(VoiceScanResult(PREVIEW_AI_SCORE, listOf(ScamCue.OTP_REQUEST, ScamCue.URGENCY)), onClose = {})
    }
}

@OpenCallerPreviews
@Composable
private fun ScanResultLikelyHumanPreview() {
    OpenCallerTheme {
        ScanResultCard(VoiceScanResult(PREVIEW_HUMAN_SCORE, emptyList()), onClose = {})
    }
}
