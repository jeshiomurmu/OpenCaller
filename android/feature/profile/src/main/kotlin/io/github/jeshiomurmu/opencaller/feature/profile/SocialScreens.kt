package io.github.jeshiomurmu.opencaller.feature.profile

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import io.github.jeshiomurmu.opencaller.core.model.ContactRequest
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ProfileVisit
import io.github.jeshiomurmu.opencaller.core.model.ProfileVisits
import io.github.jeshiomurmu.opencaller.core.model.SupporterBadge
import io.github.jeshiomurmu.opencaller.core.model.VisitKind
import io.github.jeshiomurmu.opencaller.core.ui.SupporterBadgeLabel

/** "Who viewed my profile" / "Who searched for me", wired to its view model. */
@Composable
fun VisitorsRoute(
    kind: VisitKind,
    modifier: Modifier = Modifier,
    viewModel: VisitorsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(kind) { viewModel.open(kind) }
    VisitorsScreen(state = state, modifier = modifier)
}

/** The visitors of the last 90 days: named only as their visibility allows, never by number. */
@Composable
fun VisitorsScreen(
    state: VisitorsUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        Text(
            text = stringResource(if (state.kind == VisitKind.VIEW) R.string.profile_viewers else R.string.profile_searchers),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        when {
            state.loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            state.supporterRequired -> {
                Text(
                    text = stringResource(R.string.visitors_supporters_only),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            state.visits != null -> {
                VisitList(state.visits)
            }

            else -> {
                ProblemLine(state.problem)
            }
        }
    }
}

@Composable
private fun VisitList(visits: ProfileVisits) {
    Text(
        text = pluralStringResource(R.plurals.visitors_total, visits.total, visits.total, visits.days),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (visits.items.isEmpty()) Text(text = stringResource(R.string.visitors_empty), style = MaterialTheme.typography.bodyLarge)
    LazyColumn {
        items(visits.items) { visit -> VisitRow(visit) }
    }
}

@Composable
private fun VisitRow(visit: ProfileVisit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = OpenCallerSpacing.minTouchTarget).padding(vertical = OpenCallerSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = visit.name ?: stringResource(R.string.visitors_hidden_name), style = MaterialTheme.typography.titleMedium)
            visit.badge?.let { SupporterBadgeLabel(it) }
        }
        Text(
            text = relativeTime(visit.atMillis),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Contact requests to the user, wired to its view model. */
@Composable
fun ContactRequestsRoute(
    modifier: Modifier = Modifier,
    viewModel: ContactRequestsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    ContactRequestsScreen(state = state, onAnswer = viewModel::answer, modifier = modifier)
}

/** Pending requests: who asks (their number and name), and Share my name / Don't share. */
@Composable
fun ContactRequestsScreen(
    state: ContactRequestsUiState,
    onAnswer: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        Text(
            text =
                stringResource(
                    R.string.profile_requests,
                ),
            style = MaterialTheme.typography.headlineMedium,
            modifier =
                Modifier.semantics {
                    heading()
                },
        )
        Text(text = stringResource(R.string.requests_explain), style = MaterialTheme.typography.bodyMedium)
        ProblemLine(state.problem)
        when {
            state.loading -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            }

            state.requests.isEmpty() -> {
                Text(text = stringResource(R.string.requests_empty), style = MaterialTheme.typography.bodyLarge)
            }

            else -> {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
                    items(state.requests, key = { it.id }) { request -> RequestRow(request, request.id in state.busy, onAnswer) }
                }
            }
        }
    }
}

@Composable
private fun RequestRow(
    request: ContactRequest,
    busy: Boolean,
    onAnswer: (Long, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xxs)) {
        Text(text = request.requesterName ?: request.requester.e164, style = MaterialTheme.typography.titleMedium)
        if (request.requesterName != null) Text(text = request.requester.e164, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
            Button(onClick = { onAnswer(request.id, true) }, enabled = !busy) { Text(text = stringResource(R.string.requests_approve)) }
            OutlinedButton(
                onClick = { onAnswer(request.id, false) },
                enabled = !busy,
            ) { Text(text = stringResource(R.string.requests_deny)) }
        }
    }
}

@Composable
private fun relativeTime(atMillis: Long): String =
    if (atMillis <= 0) {
        stringResource(R.string.visitors_unknown_time)
    } else {
        DateUtils.getRelativeTimeSpanString(atMillis).toString()
    }

@OpenCallerPreviews
@Composable
private fun SocialScreensPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                VisitorsScreen(
                    VisitorsUiState(
                        loading = false,
                        visits =
                            ProfileVisits(
                                VisitKind.VIEW,
                                PREVIEW_DAYS,
                                2,
                                listOf(ProfileVisit("Vera", SupporterBadge.GOLD, 0), ProfileVisit(null, null, 0)),
                            ),
                    ),
                )
                ContactRequestsScreen(
                    ContactRequestsUiState(loading = false, requests = listOf(ContactRequest(1, PhoneNumber("+12015550123"), "Rafi", 0))),
                    onAnswer = { _, _ -> },
                )
            }
        }
    }
}

private const val PREVIEW_DAYS = 90
