package io.github.jeshiomurmu.opencaller.feature.contacts

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.ContactSummary
import io.github.jeshiomurmu.opencaller.core.ui.EmptyState
import io.github.jeshiomurmu.opencaller.core.ui.PermissionExplainer
import kotlinx.coroutines.launch

/** The contacts list, wired to its view model; the contacts permission is asked for after an explainer. */
@Composable
fun ContactsListRoute(
    onOpen: (Long) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactsListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.onEvent(ContactsListEvent.Reload) }
    LifecycleResumeEffect(Unit) {
        viewModel.onEvent(ContactsListEvent.Reload)
        onPauseOrDispose {}
    }
    Box(modifier = modifier.fillMaxSize()) {
        when (state.access) {
            ContactsAccess.CHECKING -> Unit
            ContactsAccess.NEEDS_PERMISSION -> ContactsPermission(onAllow = { ask.launch(Manifest.permission.READ_CONTACTS) })
            ContactsAccess.READY -> ContactsListScreen(state = state, onEvent = viewModel::onEvent, onOpen = onOpen)
        }
        if (state.access == ContactsAccess.READY) {
            FloatingActionButton(
                onClick = onCreate,
                modifier = Modifier.align(Alignment.BottomEnd).padding(OpenCallerSpacing.screenPadding),
            ) {
                Icon(painter = painterResource(OpenCallerIcons.PersonAdd), contentDescription = stringResource(R.string.contacts_create))
            }
        }
    }
}

@Composable
private fun ContactsPermission(onAllow: () -> Unit) {
    var explaining by rememberSaveable { mutableStateOf(true) }
    if (explaining) {
        PermissionExplainer(
            icon = OpenCallerIcons.Contacts,
            title = stringResource(R.string.contacts_permission_title),
            why = stringResource(R.string.contacts_permission_why),
            never = stringResource(R.string.contacts_permission_never),
            onContinue = onAllow,
            onNotNow = { explaining = false },
        )
    } else {
        EmptyState(
            icon = OpenCallerIcons.Contacts,
            message = stringResource(R.string.contacts_permission_needed),
            actionLabel = stringResource(R.string.contacts_allow),
            onAction = onAllow,
        )
    }
}

/** Search, favourites, everyone by letter, and the letters to jump with. */
@Composable
fun ContactsListScreen(
    state: ContactsListUiState,
    onEvent: (ContactsListEvent) -> Unit,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { onEvent(ContactsListEvent.Search(it)) },
            placeholder = { Text(stringResource(R.string.contacts_search_hint)) },
            leadingIcon = { Icon(painter = painterResource(OpenCallerIcons.Search), contentDescription = null) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.xs),
        )
        when {
            state.failed -> {
                EmptyState(icon = OpenCallerIcons.Contacts, message = stringResource(R.string.contacts_failed))
            }

            state.sections.isEmpty() -> {
                EmptyState(
                    icon = OpenCallerIcons.Contacts,
                    message = stringResource(if (state.query.isBlank()) R.string.contacts_empty else R.string.contacts_no_match),
                )
            }

            else -> {
                Row(modifier = Modifier.fillMaxSize()) {
                    ContactList(state = state, listState = listState, onOpen = onOpen, modifier = Modifier.weight(1f))
                    FastScroller(
                        letters = state.sections.map { it.letter },
                        onLetter = { index -> scope.launch { listState.scrollToItem(headerIndex(state, index)) } },
                    )
                }
            }
        }
    }
}

// The list position of section [section]'s header: after the favourites block, past the earlier sections' rows.
private fun headerIndex(
    state: ContactsListUiState,
    section: Int,
): Int {
    val favourites = if (state.favourites.isEmpty()) 0 else state.favourites.size + 1
    return favourites + state.sections.take(section).sumOf { it.contacts.size + 1 }
}

@Composable
private fun ContactList(
    state: ContactsListUiState,
    listState: LazyListState,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(state = listState, modifier = modifier) {
        if (state.favourites.isNotEmpty()) {
            item(key = "favourites") { Header(stringResource(R.string.contacts_favourites)) }
            items(state.favourites, key = { "fav/${it.id}" }) { ContactRow(it, onOpen) }
        }
        state.sections.forEach { section ->
            item(key = "letter/${section.letter}") { Header(section.letter) }
            items(section.contacts, key = { it.id }) { ContactRow(it, onOpen) }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.xs).semantics { heading() },
    )
}

@Composable
private fun ContactRow(
    contact: ContactSummary,
    onOpen: (Long) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = OpenCallerSpacing.minTouchTarget)
                .clickable(role = Role.Button) { onOpen(contact.id) }
                .padding(horizontal = OpenCallerSpacing.screenPadding, vertical = OpenCallerSpacing.xs),
    ) {
        Monogram(contact.name)
        Text(
            text = contact.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (contact.starred) {
            Icon(
                painter = painterResource(OpenCallerIcons.StarFilled),
                contentDescription = stringResource(R.string.contacts_favourite),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** A 40 dp circle with the contact's initial (docs/design.md §10: a neutral monogram, no photos yet). */
@Composable
internal fun Monogram(
    name: String,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(OpenCallerSpacing.avatar).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
    ) {
        Text(
            text = ContactIndex.letterOf(name),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@OpenCallerPreviews
@Composable
private fun ContactsListScreenPreview() {
    val ram = ContactSummary(1, "a", "Ram Kumar", true, emptyList())
    val rita = ContactSummary(2, "b", "Rita", false, emptyList())
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ContactsListScreen(
                state =
                    ContactsListUiState(
                        ContactsAccess.READY,
                        favourites = listOf(ram),
                        sections = listOf(ContactSection("R", listOf(ram, rita))),
                    ),
                onEvent = {},
                onOpen = {},
            )
        }
    }
}
