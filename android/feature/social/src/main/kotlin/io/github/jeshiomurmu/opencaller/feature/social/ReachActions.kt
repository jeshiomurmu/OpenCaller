package io.github.jeshiomurmu.opencaller.feature.social

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.data.voip.AppCallIntents
import io.github.jeshiomurmu.opencaller.core.data.voip.Reachability
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.FlashKind
import timber.log.Timber

/**
 * "App call" and "Flash" for [e164] (another number's profile), shown only when the user is signed in, the server
 * offers them and, for calls, the build has them.
 */
@Composable
fun ReachActions(
    e164: String,
    modifier: Modifier = Modifier,
    viewModel: ReachViewModel = hiltViewModel(),
) {
    val reachability by viewModel.reachability.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ReachActionsContent(
        reachability = reachability,
        outcome = outcome,
        onAppCall = { startAppCall(context, e164) },
        onFlash = { kind -> viewModel.sendFlash(e164, kind) },
        modifier = modifier,
    )
}

/** Starts an app call to [e164] on the app-call screen (`feature:voip`). */
fun startAppCall(
    context: Context,
    e164: String,
) {
    val intent =
        Intent(AppCallIntents.ACTION_CALL)
            .setPackage(context.packageName)
            .putExtra(AppCallIntents.EXTRA_NUMBER, e164)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "No app-call screen in this build")
    }
}

@Composable
internal fun ReachActionsContent(
    reachability: Reachability,
    outcome: FlashOutcome?,
    onAppCall: () -> Unit,
    onFlash: (FlashKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!reachability.canCall && !reachability.canFlash) return
    var picking by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().padding(OpenCallerSpacing.screenPadding)) {
        Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
            if (reachability.canCall) Button(onClick = onAppCall) { Text(stringResource(R.string.reach_app_call)) }
            if (reachability.canFlash) OutlinedButton(onClick = { picking = true }) { Text(stringResource(R.string.flash_send)) }
        }
        Text(
            text =
                when (outcome) {
                    null -> stringResource(R.string.reach_explanation)
                    FlashOutcome.Sent -> stringResource(R.string.flash_sent)
                    is FlashOutcome.Failed -> stringResource(R.string.flash_not_sent, stringResource(outcome.reason))
                },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = OpenCallerSpacing.xs),
        )
    }
    if (picking) {
        FlashPicker(
            onPick = {
                picking = false
                onFlash(it)
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun FlashPicker(
    onPick: (FlashKind) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flash_pick_title)) },
        text = {
            Column {
                FlashKind.entries.forEach { kind ->
                    TextButton(onClick = { onPick(kind) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(kind.label())) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OpenCallerPreviews
@Composable
private fun ReachActionsPreview() {
    OpenCallerTheme {
        ReachActionsContent(Reachability(canCall = true, canFlash = true), outcome = FlashOutcome.Sent, onAppCall = {}, onFlash = {})
    }
}
