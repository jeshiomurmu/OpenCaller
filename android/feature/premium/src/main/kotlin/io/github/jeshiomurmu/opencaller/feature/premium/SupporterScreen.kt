package io.github.jeshiomurmu.opencaller.feature.premium

import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.Entitlement
import io.github.jeshiomurmu.opencaller.core.model.EntitlementSource
import io.github.jeshiomurmu.opencaller.core.model.PaidTier
import io.github.jeshiomurmu.opencaller.core.model.SupporterPlan
import java.text.DateFormat
import java.util.Date

/** Me → Supporter, wired to its view model. */
@Composable
fun SupporterRoute(
    modifier: Modifier = Modifier,
    viewModel: SupporterViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    LaunchedEffect(Unit) { viewModel.refresh() }
    SupporterScreen(
        state = state,
        onBuy = { plan -> activity?.let { viewModel.buy(it, plan) } },
        onRestore = { viewModel.refresh(restoring = true) },
        modifier = modifier,
    )
}

/** Status, what supporters get, and either the store's plans (play) or the donation link (foss). */
@Composable
fun SupporterScreen(
    state: SupporterUiState,
    onBuy: (SupporterPlan) -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.m),
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenCallerSpacing.screenPadding),
    ) {
        Text(
            stringResource(R.string.supporter_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.supporter_intro), style = MaterialTheme.typography.bodyMedium)
        if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.note?.let {
            Text(stringResource(it.textRes()), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        state.problem?.let {
            Text(stringResource(it.textRes()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Status(state.entitlement)
        Benefits()
        if (state.storeAvailable) Plans(state, onBuy, onRestore) else Donate(state.donationUrl)
    }
}

@Composable
private fun Status(entitlement: Entitlement) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs), modifier = Modifier.padding(OpenCallerSpacing.m)) {
            Text(
                stringResource(
                    R.string.supporter_status_title,
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier =
                    Modifier.semantics {
                        heading()
                    },
            )
            val tier = entitlement.tier
            Text(
                text = stringResource(tier?.statusRes() ?: R.string.supporter_status_none),
                style = MaterialTheme.typography.bodyLarge,
                color = if (tier == PaidTier.GOLD) OpenCallerTheme.semanticColors.gold else MaterialTheme.colorScheme.onSurface,
            )
            StatusDetails(entitlement)
        }
    }
}

// Where the entitlement comes from, and when a purchase renews or ends.
@Composable
private fun StatusDetails(entitlement: Entitlement) {
    val source =
        when (entitlement.source) {
            EntitlementSource.SELF_HOSTED -> R.string.supporter_status_self_hosted
            EntitlementSource.GRANTED -> R.string.supporter_status_granted
            else -> null
        }
    source?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
    entitlement.expiresAtMillis?.let { millis ->
        val date = DateFormat.getDateInstance(DateFormat.LONG).format(Date(millis))
        Text(
            text = stringResource(if (entitlement.autoRenewing) R.string.supporter_status_renews else R.string.supporter_status_ends, date),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun Benefits() {
    Text(
        stringResource(R.string.supporter_benefits_title),
        style = MaterialTheme.typography.titleMedium,
        modifier =
            Modifier.semantics {
                heading()
            },
    )
    listOf(
        R.string.supporter_benefit_extended,
        R.string.supporter_benefit_ghost,
        R.string.supporter_benefit_announce,
        R.string.supporter_benefit_backup,
        R.string.supporter_benefit_messenger,
        R.string.supporter_benefit_visitors,
        R.string.supporter_benefit_gold,
    ).forEach { Text(stringResource(R.string.supporter_bullet, stringResource(it)), style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun Plans(
    state: SupporterUiState,
    onBuy: (SupporterPlan) -> Unit,
    onRestore: () -> Unit,
) {
    val context = LocalContext.current
    if (state.plans.isNotEmpty()) {
        Text(
            stringResource(R.string.supporter_plans_title),
            style = MaterialTheme.typography.titleMedium,
            modifier =
                Modifier.semantics {
                    heading()
                },
        )
    }
    state.plans.forEach { plan ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs), modifier = Modifier.padding(OpenCallerSpacing.m)) {
                Text(stringResource(plan.tier.planRes()), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.supporter_plan_price, plan.price, periodText(plan.billingPeriod)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { onBuy(plan) }, enabled = !state.busy && state.entitlement.tier != plan.tier) {
                    Text(stringResource(R.string.supporter_buy))
                }
            }
        }
    }
    OutlinedButton(onClick = onRestore, enabled = !state.busy) { Text(stringResource(R.string.supporter_restore)) }
    if (state.entitlement.source == EntitlementSource.BILLING) {
        TextButton(onClick = {
            val uri = "https://play.google.com/store/account/subscriptions?package=${context.packageName}".toUri()
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        }) { Text(stringResource(R.string.supporter_manage)) }
    }
}

@Composable
private fun Donate(url: String?) {
    val context = LocalContext.current
    Text(
        stringResource(R.string.supporter_donate_title),
        style = MaterialTheme.typography.titleMedium,
        modifier =
            Modifier.semantics {
                heading()
            },
    )
    Text(stringResource(R.string.supporter_donate_body), style = MaterialTheme.typography.bodyMedium)
    if (url != null) {
        Button(
            onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) },
        ) { Text(stringResource(R.string.supporter_donate)) }
    } else {
        Text(stringResource(R.string.supporter_no_donation_link), style = MaterialTheme.typography.bodySmall)
    }
}

@OpenCallerPreviews
@Composable
private fun SupporterPlayPreview() {
    OpenCallerTheme {
        SupporterScreen(
            state =
                SupporterUiState(
                    entitlement =
                        Entitlement(
                            true,
                            PaidTier.GOLD,
                            EntitlementSource.BILLING,
                            expiresAtMillis = 1_795_000_000_000,
                            autoRenewing = true,
                        ),
                    storeAvailable = true,
                    plans =
                        listOf(
                            SupporterPlan(PaidTier.SUPPORTER, "supporter", "€1.99", "P1M", "t1"),
                            SupporterPlan(PaidTier.GOLD, "gold", "€3.99", "P1M", "t2"),
                        ),
                ),
            onBuy = {},
            onRestore = {},
        )
    }
}

@OpenCallerPreviews
@Composable
private fun SupporterFossPreview() {
    OpenCallerTheme {
        SupporterScreen(state = SupporterUiState(donationUrl = "https://example.org/donate"), onBuy = {}, onRestore = {})
    }
}
