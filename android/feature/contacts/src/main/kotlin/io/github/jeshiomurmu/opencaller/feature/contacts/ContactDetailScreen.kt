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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.ContactDetail
import io.github.jeshiomurmu.opencaller.core.model.ContactPhone
import io.github.jeshiomurmu.opencaller.core.model.PhoneLabel
import io.github.jeshiomurmu.opencaller.core.ui.EmptyState
import io.github.jeshiomurmu.opencaller.core.ui.PermissionExplainer
import io.github.jeshiomurmu.opencaller.core.ui.PhoneLinks
import io.github.jeshiomurmu.opencaller.core.ui.RiskBanner
import io.github.jeshiomurmu.opencaller.core.ui.VerifiedBadge

/** One contact, wired to its view model. */
@Composable
fun ContactDetailRoute(
    contactId: Long,
    navigation: ContactDetailNavigation,
    modifier: Modifier = Modifier,
    viewModel: ContactDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ask =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> viewModel.onEvent(ContactDetailEvent.PermissionResult(granted)) }
    LaunchedEffect(contactId) { viewModel.onEvent(ContactDetailEvent.Open(contactId)) }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ContactDetailEffect.OpenDialPad -> navigation.onDialPad(effect.number)
            }
        }
    }
    LaunchedEffect(state.gone) { if (state.gone) navigation.onClose() }
    if (state.explainingPermission) {
        PermissionExplainer(
            icon = OpenCallerIcons.Contacts,
            title = stringResource(R.string.contacts_write_title),
            why = stringResource(R.string.contacts_write_why),
            never = stringResource(R.string.contacts_permission_never),
            onContinue = { ask.launch(Manifest.permission.WRITE_CONTACTS) },
            onNotNow = { viewModel.onEvent(ContactDetailEvent.CancelPermission) },
            modifier = modifier,
        )
    } else {
        ContactDetailScreen(state = state, onEvent = viewModel::onEvent, onEdit = navigation.onEdit, modifier = modifier)
    }
}

/** The contact's name, favourite star, Edit, and each number with what the device knows about it. */
@Composable
fun ContactDetailScreen(
    state: ContactDetailUiState,
    onEvent: (ContactDetailEvent) -> Unit,
    onEdit: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val contact = state.contact
    when {
        contact != null -> {
            ContactDetails(contact, state, onEvent, onEdit, modifier)
        }

        state.failed -> {
            EmptyState(icon = OpenCallerIcons.Contacts, message = stringResource(R.string.contacts_failed), modifier = modifier)
        }

        else -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { if (state.loading) CircularProgressIndicator() }
        }
    }
}

@Composable
private fun ContactDetails(
    contact: ContactDetail,
    state: ContactDetailUiState,
    onEvent: (ContactDetailEvent) -> Unit,
    onEdit: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenCallerSpacing.screenPadding),
    ) {
        ContactHeader(
            contact = contact,
            onToggleFavourite = { onEvent(ContactDetailEvent.ToggleFavourite) },
            onEdit = { onEdit(contact.id) },
        )
        if (state.failed) {
            FailureLine(onDismiss = { onEvent(ContactDetailEvent.DismissFailure) })
        }
        state.numbers.forEach { number ->
            HorizontalDivider()
            NumberBlock(number = number, onCall = { onEvent(ContactDetailEvent.Call(number.phone.dialString)) })
        }
        contact.emails.forEach { email ->
            HorizontalDivider()
            Text(text = email, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

// Monogram, name, the favourite star and Edit.
@Composable
private fun ContactHeader(
    contact: ContactDetail,
    onToggleFavourite: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m)) {
        Monogram(contact.name)
        Text(
            text = contact.name,
            style = MaterialTheme.typography.headlineMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        IconButton(onClick = onToggleFavourite) {
            Icon(
                painter = painterResource(if (contact.starred) OpenCallerIcons.StarFilled else OpenCallerIcons.Star),
                contentDescription =
                    stringResource(
                        if (contact.starred) R.string.contacts_unfavourite else R.string.contacts_make_favourite,
                    ),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(painter = painterResource(OpenCallerIcons.Edit), contentDescription = stringResource(R.string.contacts_edit))
        }
    }
}

@Composable
private fun FailureLine(onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.contacts_action_failed),
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.contacts_ok)) }
    }
}

// A number, its label, and what the device knows: verified business or the spam list (never the network).
@Composable
private fun NumberBlock(
    number: ContactNumber,
    onCall: () -> Unit,
) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = number.display, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(number.phone.label.labelRes()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { PhoneLinks.message(context, number.phone.dialString) }) {
                Icon(
                    painter = painterResource(OpenCallerIcons.Message),
                    contentDescription = stringResource(R.string.contacts_message, number.display),
                )
            }
            IconButton(onClick = onCall) {
                Icon(
                    painter = painterResource(OpenCallerIcons.Calls),
                    contentDescription = stringResource(R.string.contacts_call, number.display),
                )
            }
        }
        val info = number.info
        if (info?.isVerifiedBusiness == true) VerifiedBadge()
        info?.spamVerdict?.let { RiskBanner(level = it.level, category = it.category, reportCount = it.reportCount) }
    }
}

/** The label's name ("Mobile", "Work"…). */
internal fun PhoneLabel.labelRes(): Int =
    when (this) {
        PhoneLabel.MOBILE -> R.string.contacts_label_mobile
        PhoneLabel.HOME -> R.string.contacts_label_home
        PhoneLabel.WORK -> R.string.contacts_label_work
        PhoneLabel.MAIN -> R.string.contacts_label_main
        PhoneLabel.OTHER -> R.string.contacts_label_other
    }

@OpenCallerPreviews
@Composable
private fun ContactDetailScreenPreview() {
    val phone = ContactPhone(null, "+12025550143", PhoneLabel.MOBILE)
    val contact = ContactDetail(1, "a", "Ram Kumar", true, listOf(phone), listOf("ram@example.com"), rawContactId = 1)
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ContactDetailScreen(
                state =
                    ContactDetailUiState(
                        loading = false,
                        contact = contact,
                        numbers = listOf(ContactNumber(phone, "(202) 555-0143", null)),
                    ),
                onEvent = {},
                onEdit = {},
            )
        }
    }
}
