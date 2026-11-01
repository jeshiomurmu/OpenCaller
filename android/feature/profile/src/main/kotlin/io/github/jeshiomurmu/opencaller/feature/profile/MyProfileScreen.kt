package io.github.jeshiomurmu.opencaller.feature.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.SupporterBadge
import io.github.jeshiomurmu.opencaller.core.model.VisitKind
import io.github.jeshiomurmu.opencaller.core.ui.SupporterBadgeLabel

/** My profile, wired to its view model and the photo picker (the picker needs no permission). */
@Composable
fun MyProfileRoute(
    navigation: MyProfileNavigation,
    modifier: Modifier = Modifier,
    viewModel: MyProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { viewModel.onEvent(MyProfileEvent.PicturePicked(it.toString())) }
        }
    LaunchedEffect(Unit) { viewModel.onEvent(MyProfileEvent.Load) }
    LaunchedEffect(state.deleted) { if (state.deleted) navigation.onDeleted() }
    MyProfileScreen(
        state = state,
        onEvent = viewModel::onEvent,
        onPickPicture = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        navigation = navigation,
        modifier = modifier,
    )
    DeleteAccountDialogs(step = state.deleteStep, onEvent = viewModel::onEvent)
}

/** Name, picture, visibility, incognito, the profile's activity and account deletion. */
@Composable
fun MyProfileScreen(
    state: MyProfileUiState,
    onEvent: (MyProfileEvent) -> Unit,
    onPickPicture: () -> Unit,
    navigation: MyProfileNavigation,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        Text(
            text = stringResource(R.string.profile_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        if (state.loading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (state.number == null) {
            ProblemLine(state.problem)
            TextButton(onClick = { onEvent(MyProfileEvent.Load) }) { Text(text = stringResource(R.string.profile_retry)) }
        } else {
            ProfileHeader(state, onEvent, onPickPicture)
            ProfileFields(state, onEvent)
            ProfileActivity(state, navigation)
            ProfileRow(
                title = stringResource(R.string.profile_delete),
                summary = stringResource(R.string.profile_delete_summary),
                onClick = { onEvent(MyProfileEvent.Delete) },
            )
        }
    }
}

@Composable
private fun ProfileHeader(
    state: MyProfileUiState,
    onEvent: (MyProfileEvent) -> Unit,
    onPickPicture: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m)) {
        ProfilePicture(picture = state.picture, description = stringResource(R.string.profile_picture))
        Column(verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xxs)) {
            state.number?.let { Text(text = it.e164, style = MaterialTheme.typography.titleMedium) }
            state.badge?.let { SupporterBadgeLabel(it) }
            TextButton(onClick = onPickPicture, enabled = !state.busy) { Text(text = stringResource(R.string.profile_picture_change)) }
            if (state.avatarKey != null) {
                TextButton(onClick = { onEvent(MyProfileEvent.RemovePicture) }, enabled = !state.busy) {
                    Text(text = stringResource(R.string.profile_picture_remove))
                }
            }
        }
    }
}

@Composable
private fun ProfileFields(
    state: MyProfileUiState,
    onEvent: (MyProfileEvent) -> Unit,
) {
    OutlinedTextField(
        value = state.name,
        onValueChange = { onEvent(MyProfileEvent.NameChanged(it)) },
        label = { Text(text = stringResource(R.string.profile_name)) },
        supportingText = { Text(text = stringResource(R.string.profile_name_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    VisibilityChoice(selected = state.visibility, onSelect = { onEvent(MyProfileEvent.VisibilityChanged(it)) })
    IncognitoSwitch(incognito = state.incognito, onChange = { onEvent(MyProfileEvent.IncognitoChanged(it)) })
    ProblemLine(state.problem)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
        Button(onClick = { onEvent(MyProfileEvent.Save) }, enabled = state.dirty && !state.busy) {
            Text(text = stringResource(R.string.profile_save))
        }
        if (state.busy) CircularProgressIndicator()
        if (state.saved) Text(text = stringResource(R.string.profile_saved), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProfileActivity(
    state: MyProfileUiState,
    navigation: MyProfileNavigation,
) {
    Text(
        text = stringResource(R.string.profile_activity_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.semantics { heading() },
    )
    ProfileRow(
        title = stringResource(R.string.profile_viewers),
        summary = pluralStringResource(R.plurals.profile_viewers_summary, state.viewerCount, state.viewerCount),
        onClick = { navigation.onOpenVisitors(VisitKind.VIEW) },
    )
    ProfileRow(
        title = stringResource(R.string.profile_searchers),
        summary = pluralStringResource(R.plurals.profile_searchers_summary, state.searcherCount, state.searcherCount),
        onClick = { navigation.onOpenVisitors(VisitKind.SEARCH) },
    )
    ProfileRow(
        title = stringResource(R.string.profile_requests),
        summary = stringResource(R.string.profile_requests_summary),
        onClick = navigation.onOpenRequests,
        trailing = state.pendingRequests.takeIf { it > 0 }?.toString(),
    )
}

@OpenCallerPreviews
@Composable
private fun MyProfileScreenPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            MyProfileScreen(
                state =
                    MyProfileUiState(
                        loading = false,
                        number = PhoneNumber("+8801712345678"),
                        name = "Asha Rahman",
                        avatarKey = "avatars/u/a.jpg",
                        badge = SupporterBadge.GOLD,
                        viewerCount = 4,
                        searcherCount = 1,
                        pendingRequests = 2,
                        dirty = true,
                    ),
                onEvent = {},
                onPickPicture = {},
                navigation = MyProfileNavigation({}, {}, {}),
            )
        }
    }
}
