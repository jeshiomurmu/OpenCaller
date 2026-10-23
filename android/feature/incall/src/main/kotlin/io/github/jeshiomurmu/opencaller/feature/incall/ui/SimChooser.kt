package io.github.jeshiomurmu.opencaller.feature.incall.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.feature.incall.CallView
import io.github.jeshiomurmu.opencaller.feature.incall.InCallEvent
import io.github.jeshiomurmu.opencaller.feature.incall.R

/**
 * A call placed without a default SIM waits for the user to pick one. SIM names need the phone numbers permission on
 * Android 12+; without it nothing is guessed (a wrong label would place the call on the wrong SIM): the screen asks
 * for the permission, and Cancel call is always there.
 */
@Composable
internal fun SimChooser(
    view: CallView,
    onEvent: (InCallEvent) -> Unit,
    onShowNames: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val choices = view.call.simChoices
    val named = choices.isNotEmpty() && choices.all { it.label != null }
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        Text(text = titleOf(view), style = MaterialTheme.typography.headlineMedium)
        Text(
            text = stringResource(R.string.incall_sim_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        if (named) {
            choices.forEach { sim ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
                    Icon(painter = painterResource(OpenCallerIcons.SimCard), contentDescription = null)
                    SheetRow(text = sim.label.orEmpty(), onClick = { onEvent(InCallEvent.SelectSim(view.call.id, sim.id)) })
                }
            }
        } else {
            Text(text = stringResource(R.string.incall_sim_names_needed), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onShowNames, modifier = Modifier.heightIn(min = OpenCallerSpacing.minTouchTarget)) {
                Text(stringResource(R.string.incall_sim_show_names))
            }
        }
        OutlinedButton(
            onClick = { onEvent(InCallEvent.HangUp(view.call.id)) },
            modifier = Modifier.fillMaxWidth().heightIn(min = OpenCallerSpacing.minTouchTarget),
        ) {
            Text(stringResource(R.string.incall_cancel_call))
        }
    }
}

@OpenCallerPreviews
@Composable
private fun SimChooserPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            SimChooser(view = InCallSamples.choosingSim, onEvent = {}, onShowNames = {})
        }
    }
}
