package io.github.jeshiomurmu.opencaller.feature.business

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.BusinessCategory
import io.github.jeshiomurmu.opencaller.core.model.BusinessClaim
import io.github.jeshiomurmu.opencaller.core.model.ClaimMethod
import io.github.jeshiomurmu.opencaller.core.model.ClaimStatus
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber

/** The business form: number, names, category, optional details, and how the code should arrive. */
@Composable
internal fun ClaimFormStep(
    state: ClaimUiState,
    onEvent: (ClaimEvent) -> Unit,
) {
    val fields = state.fields
    val edit: (ClaimFields) -> Unit = { onEvent(ClaimEvent.EditFields(it)) }
    Heading(stringResource(R.string.claim_form_title))
    Field(fields.number, R.string.claim_number, KeyboardType.Phone) { edit(fields.copy(number = it)) }
    Field(fields.legalName, R.string.claim_legal_name) { edit(fields.copy(legalName = it)) }
    Field(fields.displayName, R.string.claim_display_name) { edit(fields.copy(displayName = it)) }
    CategoryPicker(fields.category) { edit(fields.copy(category = it)) }
    Field(fields.website, R.string.claim_website, KeyboardType.Uri) { edit(fields.copy(website = it)) }
    Field(fields.hours, R.string.claim_hours) { edit(fields.copy(hours = it)) }
    Field(fields.callReason, R.string.claim_call_reason) { edit(fields.copy(callReason = it)) }
    Text(stringResource(R.string.claim_method), style = MaterialTheme.typography.titleSmall)
    ClaimMethod.entries.forEach { method ->
        MethodOption(method, selected = fields.method == method) { edit(fields.copy(method = method)) }
    }
    Text(stringResource(R.string.claim_form_note), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
        TextButton(onClick = { onEvent(ClaimEvent.Back) }) { Text(stringResource(R.string.claim_back)) }
        Button(onClick = { onEvent(ClaimEvent.Start) }, enabled = state.canStart) { Text(stringResource(R.string.claim_send_code)) }
    }
}

/** The code sent to the business number, with a new code by text or by phone call. */
@Composable
internal fun CodeStep(
    state: ClaimUiState,
    onEvent: (ClaimEvent) -> Unit,
) {
    val claim = state.active ?: return
    Heading(stringResource(R.string.claim_code_title))
    val sentText = if (claim.method == ClaimMethod.VOICE) R.string.claim_code_voice else R.string.claim_code_sms
    Text(stringResource(sentText, claim.number.e164), style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        value = state.code,
        onValueChange = { onEvent(ClaimEvent.EditCode(it)) },
        label = { Text(stringResource(R.string.claim_code)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { onEvent(ClaimEvent.Verify) }, enabled = state.canVerify) { Text(stringResource(R.string.claim_verify)) }
    TextButton(onClick = { onEvent(ClaimEvent.Resend(ClaimMethod.SMS)) }, enabled = !state.busy) {
        Text(stringResource(R.string.claim_resend_sms))
    }
    TextButton(onClick = { onEvent(ClaimEvent.Resend(ClaimMethod.VOICE)) }, enabled = !state.busy) {
        Text(stringResource(R.string.claim_resend_voice))
    }
    TextButton(onClick = { onEvent(ClaimEvent.Back) }) { Text(stringResource(R.string.claim_back)) }
}

/** Documents (at least one) and an optional logo, then Send for review. */
@Composable
internal fun DocumentsStep(
    state: ClaimUiState,
    onEvent: (ClaimEvent) -> Unit,
    actions: ClaimActions,
) {
    Heading(stringResource(R.string.claim_documents_title))
    Text(stringResource(R.string.claim_documents_explain), style = MaterialTheme.typography.bodyLarge)
    state.documents.forEach { file ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onEvent(ClaimEvent.RemoveDocument(file.key)) }) { Text(stringResource(R.string.claim_remove)) }
        }
    }
    OutlinedButton(onClick = actions.onPickDocument, enabled = state.canAddDocument) { Text(stringResource(R.string.claim_add_document)) }
    Text(stringResource(R.string.claim_logo), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.claim_logo_explain), style = MaterialTheme.typography.bodyMedium)
    state.logo?.let { Text(it.name, style = MaterialTheme.typography.bodyMedium) }
    OutlinedButton(onClick = actions.onPickLogo, enabled = !state.busy) {
        Text(stringResource(if (state.logo == null) R.string.claim_add_logo else R.string.claim_change_logo))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
        TextButton(onClick = { onEvent(ClaimEvent.Back) }) { Text(stringResource(R.string.claim_back)) }
        Button(onClick = { onEvent(ClaimEvent.Submit) }, enabled = state.canSubmit) { Text(stringResource(R.string.claim_submit)) }
    }
}

@Composable
private fun Field(
    value: String,
    @StringRes label: Int,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.take(MAX_FIELD)) },
        label = { Text(stringResource(label)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CategoryPicker(
    selected: BusinessCategory?,
    onSelect: (BusinessCategory) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            val label = selected?.let { stringResource(R.string.claim_category_selected, stringResource(it.labelRes())) }
            Text(label ?: stringResource(R.string.claim_category))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            BusinessCategory.entries.forEach { category ->
                DropdownMenuItem(
                    text = { Text(stringResource(category.labelRes())) },
                    onClick = {
                        onSelect(category)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun MethodOption(
    method: ClaimMethod,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            stringResource(if (method == ClaimMethod.VOICE) R.string.claim_method_voice else R.string.claim_method_sms),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

// The server allows 100 characters for names and 200 for the rest; it checks again.
private const val MAX_FIELD = 200

private val PREVIEW_CLAIM =
    BusinessClaim(
        id = "c1",
        number = PhoneNumber("+8801812345678"),
        displayName = "Example Bank",
        category = BusinessCategory.BANK,
        method = ClaimMethod.SMS,
        status = ClaimStatus.VERIFIED,
        codeExpiresInSeconds = null,
        reviewNote = null,
        businessId = null,
        createdAtMillis = 0,
    )

@OpenCallerPreviews
@Composable
private fun ClaimFormPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
                val fields = ClaimFields(displayName = "Example Bank", category = BusinessCategory.BANK)
                ClaimFormStep(ClaimUiState(fields = fields), onEvent = {})
            }
        }
    }
}

@OpenCallerPreviews
@Composable
private fun DocumentsPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
                DocumentsStep(
                    ClaimUiState(active = PREVIEW_CLAIM, documents = listOf(UploadedFile("registration.pdf", "k")), logo = null),
                    onEvent = {},
                    actions = ClaimActions(),
                )
            }
        }
    }
}
