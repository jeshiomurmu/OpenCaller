package io.github.jeshiomurmu.opencaller.feature.blocking

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing

/** Countries whose calls always ring even when "Block calls from other countries" is on. */
@Composable
internal fun AllowedCountries(
    codes: List<String>,
    error: Boolean,
    onEvent: (BlockingEvent) -> Unit,
) {
    Section(stringResource(R.string.blocking_section_countries))
    Column(
        modifier = Modifier.padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
    ) {
        if (codes.isEmpty()) {
            Text(text = stringResource(R.string.blocking_countries_none), style = MaterialTheme.typography.bodyMedium)
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
        ) {
            codes.forEach { code ->
                val remove = stringResource(R.string.blocking_country_remove, countryName(code))
                InputChip(
                    selected = false,
                    onClick = { onEvent(BlockingEvent.RemoveCountry(code)) },
                    label = { Text(text = "${countryName(code)} ($code)") },
                    modifier = Modifier.semantics { contentDescription = remove },
                )
            }
        }
        CountryInput(error = error, onAdd = { onEvent(BlockingEvent.AddCountry(it)) })
    }
}

@Composable
private fun CountryInput(
    error: Boolean,
    onAdd: (String) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val submit = {
        onAdd(input)
        input = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.take(2) },
            label = { Text(text = stringResource(R.string.blocking_country_input)) },
            isError = error,
            supportingText = if (error) ({ Text(text = stringResource(R.string.blocking_country_error)) }) else null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = submit) { Text(text = stringResource(R.string.blocking_country_add)) }
    }
}

/** Confirms a spam report from the after-call notification. */
@Composable
internal fun ReportDialog(
    report: PendingReport,
    onEvent: (BlockingEvent) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onEvent(BlockingEvent.DismissReport) },
        title = { Text(text = stringResource(R.string.report_title, report.display)) },
        text = { Text(text = stringResource(R.string.report_body)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { onEvent(BlockingEvent.ConfirmReport(alsoBlock = true)) }) {
                    Text(text = stringResource(R.string.report_and_block))
                }
                TextButton(onClick = { onEvent(BlockingEvent.ConfirmReport(alsoBlock = false)) }) {
                    Text(text = stringResource(R.string.report_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onEvent(BlockingEvent.DismissReport) },
            ) { Text(text = stringResource(R.string.report_cancel)) }
        },
    )
}
