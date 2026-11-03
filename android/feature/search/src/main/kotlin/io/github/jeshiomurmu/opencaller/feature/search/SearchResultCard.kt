package io.github.jeshiomurmu.opencaller.feature.search

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.jeshiomurmu.opencaller.core.data.lookup.SearchQuota
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.isBusinessProfileId
import io.github.jeshiomurmu.opencaller.core.ui.CallerCard
import io.github.jeshiomurmu.opencaller.core.ui.CallerCardSurface
import io.github.jeshiomurmu.opencaller.core.ui.WhyButton

/** "42 of 50 searches left today", or the design.md §8 "used up" line. */
@Composable
internal fun QuotaIndicator(
    quota: SearchQuota,
    modifier: Modifier = Modifier,
) {
    Text(
        text =
            if (quota.remaining == 0) {
                pluralStringResource(R.plurals.search_quota_used, quota.limit, quota.limit)
            } else {
                pluralStringResource(R.plurals.search_quota_left, quota.remaining, quota.remaining, quota.limit)
            },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * A search result: the caller card (name with its source and supporter badge, risk, reports), "not spam" votes, a
 * review link, and "Open profile" (the profile, or a way to ask for a hidden name) for numbers without a business.
 */
@Composable
internal fun SearchResultCard(
    found: SearchFound,
    onWhy: () -> Unit,
    onDispute: () -> Unit,
    onOpenProfile: () -> Unit = {},
    onOpenBusiness: (businessId: String) -> Unit = {},
) {
    CallerCardSurface {
        Column {
            CallerCard(caller = found.caller, formattedNumber = found.formattedNumber) {
                WhyButton(onClick = onWhy)
            }
            if (found.notSpamCount > 0) {
                Text(
                    text = pluralStringResource(R.plurals.search_not_spam_count, found.notSpamCount, found.notSpamCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = OpenCallerSpacing.m),
                )
            }
            if (found.caller.spamVerdict
                    ?.level
                    ?.let { it != RiskLevel.NONE } == true
            ) {
                TextButton(onClick = onDispute, modifier = Modifier.padding(horizontal = OpenCallerSpacing.xs)) {
                    Text(text = stringResource(R.string.search_dispute))
                }
            }
            val businessId = found.caller.verifiedBusinessId?.takeIf(::isBusinessProfileId)
            if (businessId != null) {
                TextButton(onClick = { onOpenBusiness(businessId) }, modifier = Modifier.padding(horizontal = OpenCallerSpacing.xs)) {
                    Text(text = stringResource(R.string.search_open_business))
                }
            }
            if (!found.caller.isVerifiedBusiness) {
                TextButton(onClick = onOpenProfile, modifier = Modifier.padding(horizontal = OpenCallerSpacing.xs)) {
                    Text(text = stringResource(R.string.search_open_profile))
                }
            }
        }
    }
}
