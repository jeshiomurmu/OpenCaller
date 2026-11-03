package io.github.jeshiomurmu.opencaller.feature.business

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.BusinessCategory
import io.github.jeshiomurmu.opencaller.core.model.BusinessNumber
import io.github.jeshiomurmu.opencaller.core.model.BusinessProfile
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.ui.SourceChip
import io.github.jeshiomurmu.opencaller.core.ui.VerifiedBadge
import io.github.jeshiomurmu.opencaller.core.ui.decodeLogo

/** A verified business's profile wired to its view model; [businessId] comes from search or a caller card. */
@Composable
fun BusinessProfileRoute(
    businessId: String,
    modifier: Modifier = Modifier,
    viewModel: BusinessProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(businessId) { viewModel.open(businessId) }
    BusinessProfileScreen(state = state, modifier = modifier)
}

/** Name, logo, category, website, hours and numbers with their call reasons (docs/prd.md F-BZ-02). */
@Composable
fun BusinessProfileScreen(
    state: BusinessProfileUiState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val profile = state.profile
        when {
            state.loading -> CircularProgressIndicator()
            profile == null -> state.problem?.let { Text(stringResource(it.messageRes()), style = MaterialTheme.typography.bodyLarge) }
            else -> ProfileBody(profile, state.logo)
        }
    }
}

@Composable
private fun ProfileBody(
    profile: BusinessProfile,
    logo: LogoBytes?,
) {
    val bitmap = remember(logo) { logo?.let { decodeLogo(it.bytes) } }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = stringResource(R.string.business_logo, profile.name),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(LOGO_SIZE).clip(CircleShape),
        )
    }
    Text(profile.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
    SourceChip(source = NameSource.VERIFIED_BUSINESS)
    VerifiedBadge()
    profile.category?.let { Text(stringResource(it.labelRes()), style = MaterialTheme.typography.bodyLarge) }
    profile.website?.let { website ->
        val uriHandler = LocalUriHandler.current
        TextButton(onClick = { uriHandler.openUri(website) }) { Text(stringResource(R.string.business_website, website)) }
    }
    profile.hours?.let { Text(stringResource(R.string.business_hours, it), style = MaterialTheme.typography.bodyMedium) }
    HorizontalDivider()
    Text(stringResource(R.string.business_numbers), style = MaterialTheme.typography.titleMedium)
    profile.numbers.forEach { number ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(number.number.e164, style = MaterialTheme.typography.bodyLarge)
            number.callReason?.let { Text(stringResource(R.string.business_call_reason, it), style = MaterialTheme.typography.bodyMedium) }
        }
    }
    Text(stringResource(R.string.business_profile_note), style = MaterialTheme.typography.bodySmall)
}

private val LOGO_SIZE = OpenCallerSpacing.avatar * 2

@OpenCallerPreviews
@Composable
private fun BusinessProfilePreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            BusinessProfileScreen(
                state =
                    BusinessProfileUiState(
                        loading = false,
                        profile =
                            BusinessProfile(
                                id = "0b6a3c52-1f0e-4f7b-9d2a-5c1e8f3a7b10",
                                name = "Example Bank",
                                category = BusinessCategory.BANK,
                                website = "https://bank.example",
                                hours = "Sun–Thu 9–17",
                                logoUrl = null,
                                numbers = listOf(BusinessNumber(PhoneNumber("+8801812345678"), "Card security")),
                            ),
                    ),
            )
        }
    }
}
