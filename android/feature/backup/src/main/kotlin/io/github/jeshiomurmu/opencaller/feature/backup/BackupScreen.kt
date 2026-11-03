package io.github.jeshiomurmu.opencaller.feature.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.ui.SupporterFeatureCard

/** Settings → Backup and restore, wired to its view model and the system's file and consent screens. */
@Composable
fun BackupRoute(
    onOpenSupporter: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val save =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME)) { uri ->
            uri?.let { viewModel.onEvent(BackupEvent.SaveTo(it.toString())) }
        }
    val open =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { viewModel.onEvent(BackupEvent.RestoreFrom(it.toString())) }
        }
    DriveConsent(state, viewModel::onEvent)
    val fileName = stringResource(R.string.backup_file_name)
    BackupScreen(
        state = state,
        actions =
            BackupActions(
                onEvent = viewModel::onEvent,
                // The file picker comes only once the passphrase checks out, so a refused backup never leaves an empty file.
                onSaveToFile = {
                    if (viewModel.backupReady() == null) save.launch(fileName) else viewModel.onEvent(BackupEvent.ExplainNotReady)
                },
                onRestoreFromFile = { open.launch(arrayOf(MIME, ANY)) },
                onOpenSupporter = onOpenSupporter,
            ),
        modifier = modifier,
    )
}

// Google's consent screen for Drive, shown when the view model asks for it.
@Composable
private fun DriveConsent(
    state: BackupUiState,
    onEvent: (BackupEvent) -> Unit,
) {
    val consent =
        rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
            onEvent(BackupEvent.ConsentResult(it.data))
        }
    LaunchedEffect(state.consent) {
        state.consent?.let {
            onEvent(BackupEvent.ConsentShown)
            consent.launch(IntentSenderRequest.Builder(it).build())
        }
    }
}

/** What the backup screen's buttons do. */
data class BackupActions(
    val onEvent: (BackupEvent) -> Unit,
    val onSaveToFile: () -> Unit,
    val onRestoreFromFile: () -> Unit,
    val onOpenSupporter: () -> Unit,
)

/** Passphrase, then file and (play) Google Drive actions, with what happened. */
@Composable
fun BackupScreen(
    state: BackupUiState,
    actions: BackupActions,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenCallerSpacing.screenPadding),
    ) {
        Text(
            stringResource(R.string.backup_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.backup_intro), style = MaterialTheme.typography.bodyMedium)
        if (!state.entitled) SupporterFeatureCard(onOpenSupporter = actions.onOpenSupporter)
        Passphrases(state, actions.onEvent)
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Outcome(state)
        Text(
            stringResource(R.string.backup_file_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Button(onClick = actions.onSaveToFile, enabled = !state.busy && state.entitled) { Text(stringResource(R.string.backup_to_file)) }
        OutlinedButton(onClick = actions.onRestoreFromFile, enabled = !state.busy) { Text(stringResource(R.string.backup_from_file)) }
        if (state.cloudAvailable) Drive(state, actions.onEvent)
        Text(
            stringResource(R.string.backup_restore_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Passphrases(
    state: BackupUiState,
    onEvent: (BackupEvent) -> Unit,
) {
    OutlinedTextField(
        value = state.passphrase,
        onValueChange = { onEvent(BackupEvent.Passphrase(it)) },
        label = { Text(stringResource(R.string.backup_passphrase)) },
        supportingText = { Text(stringResource(R.string.backup_passphrase_hint)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.confirm,
        onValueChange = { onEvent(BackupEvent.Confirm(it)) },
        label = { Text(stringResource(R.string.backup_confirm)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Drive(
    state: BackupUiState,
    onEvent: (BackupEvent) -> Unit,
) {
    Text(
        stringResource(R.string.backup_drive_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    Text(stringResource(R.string.backup_drive_body), style = MaterialTheme.typography.bodyMedium)
    Button(onClick = {
        onEvent(BackupEvent.DriveBackup)
    }, enabled = !state.busy && state.entitled) { Text(stringResource(R.string.backup_to_drive)) }
    OutlinedButton(
        onClick = { onEvent(BackupEvent.DriveRestore) },
        enabled = !state.busy,
    ) { Text(stringResource(R.string.backup_from_drive)) }
}

@Composable
private fun Outcome(state: BackupUiState) {
    when (val note = state.note) {
        BackupNote.Saved -> {
            Text(stringResource(R.string.backup_saved), color = MaterialTheme.colorScheme.primary)
        }

        is BackupNote.Restored -> {
            Text(
                stringResource(
                    R.string.backup_restored,
                    note.summary.rules,
                    note.summary.events,
                    note.summary.messages,
                    note.summary.messagesNotFound,
                ),
                color = MaterialTheme.colorScheme.primary,
            )
        }

        null -> {} // nothing done yet
    }
    state.problem?.let { Text(stringResource(it.textRes()), color = MaterialTheme.colorScheme.error) }
}

private fun BackupProblem.textRes(): Int =
    when (this) {
        BackupProblem.NOT_ENTITLED -> R.string.backup_problem_not_entitled
        BackupProblem.PASSPHRASE_TOO_SHORT -> R.string.backup_problem_short
        BackupProblem.PASSPHRASES_DIFFER -> R.string.backup_problem_differ
        BackupProblem.WRONG_PASSPHRASE -> R.string.backup_problem_wrong
        BackupProblem.NOT_A_BACKUP -> R.string.backup_problem_not_backup
        BackupProblem.NO_DRIVE_BACKUP -> R.string.backup_problem_no_drive
        BackupProblem.DRIVE_REFUSED -> R.string.backup_problem_drive
        BackupProblem.OFFLINE -> R.string.backup_problem_offline
        BackupProblem.FAILED -> R.string.backup_problem_failed
    }

private const val MIME = "application/octet-stream"
private const val ANY = "*/*"

@OpenCallerPreviews
@Composable
private fun BackupScreenPreview() {
    OpenCallerTheme {
        BackupScreen(BackupUiState(entitled = true, cloudAvailable = true), BackupActions({}, {}, {}, {}))
    }
}
