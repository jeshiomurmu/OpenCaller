package io.github.jeshiomurmu.opencaller.feature.dialer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.telephony.SimOption

/** "Call with": the SIM picker for a phone with two SIMs and no default (TC-DIAL-003). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SimPickerSheet(
    options: List<SimOption>,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SimOptions(options = options, onChoose = onChoose, modifier = Modifier.navigationBarsPadding())
    }
}

@Composable
private fun SimOptions(
    options: List<SimOption>,
    onChoose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.dialpad_sim_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        options.forEach { option ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = OpenCallerSpacing.minTouchTarget)
                        .clickable(role = Role.Button) { onChoose(option.id) }
                        .padding(vertical = OpenCallerSpacing.s),
            ) {
                Icon(painter = painterResource(OpenCallerIcons.SimCard), contentDescription = null)
                Text(text = option.label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@OpenCallerPreviews
@Composable
private fun SimOptionsPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            SimOptions(options = listOf(SimOption("a", "Airtel"), SimOption("b", "Work")), onChoose = {})
        }
    }
}
