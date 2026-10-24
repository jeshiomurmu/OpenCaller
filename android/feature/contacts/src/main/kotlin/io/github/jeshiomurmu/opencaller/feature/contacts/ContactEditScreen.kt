package io.github.jeshiomurmu.opencaller.feature.contacts

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.ContactDraft
import io.github.jeshiomurmu.opencaller.core.model.ContactPhoneDraft
import io.github.jeshiomurmu.opencaller.core.model.PhoneLabel
import io.github.jeshiomurmu.opencaller.core.ui.PermissionExplainer

/**
 * Create a contact ([contactId] `null`, [number] filled in when given) or edit one, wired to its view model. The
 * contacts write permission is asked for on Save, after an explainer. [onSaved] gets the saved contact's id.
 */
@Composable
fun ContactEditRoute(
    contactId: Long?,
    number: String?,
    onSaved: (Long) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactEditViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ask =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> viewModel.onEvent(ContactEditEvent.PermissionResult(granted)) }
    LaunchedEffect(contactId, number) { viewModel.onEvent(ContactEditEvent.Prepare(contactId, number)) }
    LaunchedEffect(state.savedContactId) { state.savedContactId?.let(onSaved) }
    if (state.explainingPermission) {
        PermissionExplainer(
            icon = OpenCallerIcons.Contacts,
            title = stringResource(R.string.contacts_write_title),
            why = stringResource(R.string.contacts_write_why),
            never = stringResource(R.string.contacts_permission_never),
            onContinue = { ask.launch(Manifest.permission.WRITE_CONTACTS) },
            onNotNow = { viewModel.onEvent(ContactEditEvent.CancelPermission) },
            modifier = modifier,
        )
    } else {
        ContactEditScreen(state = state, onEvent = viewModel::onEvent, onCancel = onCancel, modifier = modifier)
    }
}

/** Name, numbers with their labels, e-mail addresses; Cancel and Save. */
@Composable
fun ContactEditScreen(
    state: ContactEditUiState,
    onEvent: (ContactEditEvent) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenCallerSpacing.screenPadding),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(if (state.isNew) R.string.contacts_create else R.string.contacts_edit),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(onClick = onCancel) { Text(stringResource(R.string.contacts_cancel)) }
            Button(onClick = { onEvent(ContactEditEvent.Save) }, enabled = !state.saving) { Text(stringResource(R.string.contacts_save)) }
        }
        state.problem?.let { Text(text = stringResource(it.messageRes()), color = MaterialTheme.colorScheme.error) }
        OutlinedTextField(
            value = state.draft.name,
            onValueChange = { onEvent(ContactEditEvent.Name(it)) },
            label = { Text(stringResource(R.string.contacts_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        PhoneFields(draft = state.draft, onEvent = onEvent)
        EmailFields(draft = state.draft, onEvent = onEvent)
    }
}

@Composable
private fun PhoneFields(
    draft: ContactDraft,
    onEvent: (ContactEditEvent) -> Unit,
) {
    draft.phones.forEachIndexed { index, phone ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
            OutlinedTextField(
                value = phone.dialString,
                onValueChange = { onEvent(ContactEditEvent.Phone(index, it)) },
                label = { Text(stringResource(R.string.contacts_phone)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f),
            )
            LabelPicker(label = phone.label, onPick = { onEvent(ContactEditEvent.Label(index, it)) })
            if (draft.phones.size > 1) {
                IconButton(onClick = { onEvent(ContactEditEvent.RemovePhone(index)) }) {
                    Icon(
                        painter = painterResource(OpenCallerIcons.Delete),
                        contentDescription = stringResource(R.string.contacts_remove_phone),
                    )
                }
            }
        }
    }
    TextButton(onClick = { onEvent(ContactEditEvent.AddPhone) }) { Text(stringResource(R.string.contacts_add_phone)) }
}

// The e-mail addresses, plus one empty field to add another.
@Composable
private fun EmailFields(
    draft: ContactDraft,
    onEvent: (ContactEditEvent) -> Unit,
) {
    (draft.emails + "").forEachIndexed { index, email ->
        OutlinedTextField(
            value = email,
            onValueChange = { onEvent(ContactEditEvent.Email(index, it)) },
            label = { Text(stringResource(R.string.contacts_email)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LabelPicker(
    label: PhoneLabel,
    onPick: (PhoneLabel) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(label.labelRes())) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PhoneLabel.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.labelRes())) },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

private fun ContactEditProblem.messageRes(): Int =
    when (this) {
        ContactEditProblem.EMPTY -> R.string.contacts_problem_empty
        ContactEditProblem.FAILED -> R.string.contacts_problem_failed
        ContactEditProblem.NO_PERMISSION -> R.string.contacts_problem_permission
    }

@OpenCallerPreviews
@Composable
private fun ContactEditScreenPreview() {
    val draft = ContactDraft(name = "Ram Kumar", phones = listOf(ContactPhoneDraft("+12025550143"), ContactPhoneDraft("", PhoneLabel.WORK)))
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ContactEditScreen(state = ContactEditUiState(loading = false, isNew = false, draft = draft), onEvent = {}, onCancel = {})
        }
    }
}
