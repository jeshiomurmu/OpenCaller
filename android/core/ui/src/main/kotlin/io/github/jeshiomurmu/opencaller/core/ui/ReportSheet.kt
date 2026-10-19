package io.github.jeshiomurmu.opencaller.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.ReportCategory
import io.github.jeshiomurmu.opencaller.core.model.ReportLabel

/**
 * Report sheet (docs/architecture.md §4.8): spam or not spam, a category from the server's list, an optional
 * comment and "Also block". [signedIn] false explains that the report waits until the user signs in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportSheet(
    number: String,
    signedIn: Boolean,
    onSubmit: (ReportDraft) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    initialLabel: ReportLabel = ReportLabel.SPAM,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        ReportForm(
            number = number,
            signedIn = signedIn,
            onSubmit = onSubmit,
            onCancel = onDismiss,
            initialLabel = initialLabel,
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

/** The report sheet's content. */
@Composable
fun ReportForm(
    number: String,
    signedIn: Boolean,
    onSubmit: (ReportDraft) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    initialLabel: ReportLabel = ReportLabel.SPAM,
) {
    var label by rememberSaveable { mutableStateOf(initialLabel) }
    var category by rememberSaveable { mutableStateOf<ReportCategory?>(null) }
    var comment by rememberSaveable { mutableStateOf("") }
    var alsoBlock by rememberSaveable { mutableStateOf(true) }
    val spam = label == ReportLabel.SPAM
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
    ) {
        Text(
            text = stringResource(R.string.report_sheet_title, number),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        LabelChoice(spam = spam, onSelect = { label = it })
        if (spam) {
            CategoryChoice(selected = category, onSelect = { category = it })
            CheckRow(stringResource(R.string.report_also_block), checked = alsoBlock) { alsoBlock = it }
        }
        CommentField(comment = comment, onChange = { comment = it })
        Text(
            text = stringResource(if (signedIn) R.string.report_privacy else R.string.report_waits_for_sign_in),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ReportButtons(onCancel = onCancel, onSend = {
            onSubmit(
                ReportDraft(
                    label,
                    category.takeIf { spam },
                    comment.trim(),
                    alsoBlock && spam,
                ),
            )
        })
    }
}

@Composable
private fun ReportButtons(
    onCancel: () -> Unit,
    onSend: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs, Alignment.End)) {
        TextButton(onClick = onCancel) { Text(text = stringResource(R.string.report_cancel)) }
        Button(onClick = onSend, shape = OpenCallerShape.pill) { Text(text = stringResource(R.string.report_send)) }
    }
}

@Composable
private fun LabelChoice(
    spam: Boolean,
    onSelect: (ReportLabel) -> Unit,
) {
    Column(modifier = Modifier.selectableGroup()) {
        ChoiceRow(stringResource(R.string.report_label_spam), selected = spam) { onSelect(ReportLabel.SPAM) }
        ChoiceRow(stringResource(R.string.report_label_not_spam), selected = !spam) { onSelect(ReportLabel.NOT_SPAM) }
    }
}

@Composable
private fun CommentField(
    comment: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = comment,
        onValueChange = { onChange(it.take(REPORT_COMMENT_MAX_LENGTH)) },
        label = { Text(text = stringResource(R.string.report_comment)) },
        supportingText = { Text(text = stringResource(R.string.report_comment_hint)) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun CategoryChoice(
    selected: ReportCategory?,
    onSelect: (ReportCategory?) -> Unit,
) {
    Text(
        text = stringResource(R.string.report_category),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = OpenCallerSpacing.xs),
    )
    Column(modifier = Modifier.selectableGroup()) {
        ReportCategory.entries.forEach { category ->
            ChoiceRow(stringResource(category.labelRes()), selected = selected == category) {
                onSelect(category.takeIf { it != selected })
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = OpenCallerSpacing.minTouchTarget)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CheckRow(
    text: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = OpenCallerSpacing.minTouchTarget)
                .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text = text, style = MaterialTheme.typography.bodyLarge)
    }
}

@OpenCallerPreviews
@Composable
private fun ReportFormPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ReportForm(number = "+1 202-555-0143", signedIn = false, onSubmit = {}, onCancel = {})
        }
    }
}
