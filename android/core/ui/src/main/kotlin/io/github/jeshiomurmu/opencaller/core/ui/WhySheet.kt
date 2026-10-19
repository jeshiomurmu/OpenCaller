package io.github.jeshiomurmu.opencaller.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme

/** "Why am I seeing this?" as a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhySheet(
    reasons: List<WhyReason>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = modifier,
    ) {
        WhyExplanation(reasons = reasons, modifier = Modifier.navigationBarsPadding().padding(bottom = OpenCallerSpacing.m))
    }
}

/** The title, one line per reason and what OpenCaller never sends. Also shown inline on the caller card overlay. */
@Composable
fun WhyExplanation(
    reasons: List<WhyReason>,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = OpenCallerSpacing.screenPadding),
        verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
    ) {
        if (showTitle) {
            Text(
                text = stringResource(R.string.why_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        reasons.forEach { reason -> WhyLine(text = reason.text()) }
        WhyLine(text = stringResource(R.string.why_privacy), icon = OpenCallerIcons.Privacy)
    }
}

/** A "Why am I seeing this?" link under a caller card or a result. */
@Composable
fun WhyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier) { Text(text = stringResource(R.string.why_title)) }
}

@Composable
private fun WhyLine(
    text: String,
    icon: Int = OpenCallerIcons.Protection,
) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(OpenCallerSpacing.iconSmall),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun WhyReason.text(): String =
    if (this is WhyReason.Reports) pluralStringResource(R.plurals.why_reports, count, count) else stringResource(WHY_TEXT.getValue(this))

// One sentence per reason; Reports is a plural (above).
private val WHY_TEXT: Map<WhyReason, Int> =
    mapOf(
        WhyReason.YourRule to R.string.why_rule,
        WhyReason.HiddenSetting to R.string.why_hidden,
        WhyReason.ForeignSetting to R.string.why_foreign,
        WhyReason.NonContactSetting to R.string.why_non_contact,
        WhyReason.Contact to R.string.why_contact,
        WhyReason.SelfRegistered to R.string.why_self_registered,
        WhyReason.VerifiedBusiness to R.string.why_verified_business,
        WhyReason.SpamList to R.string.why_spam_list,
        WhyReason.EarlierLookup to R.string.why_earlier_lookup,
        WhyReason.LiveLookup to R.string.why_live_lookup,
        WhyReason.Searched to R.string.why_searched,
        WhyReason.CarrierVerified to R.string.why_stir_passed,
        WhyReason.CarrierFailed to R.string.why_stir_failed,
        WhyReason.NoData to R.string.caller_card_no_data,
    )

@OpenCallerPreviews
@Composable
private fun WhyExplanationPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            WhyExplanation(
                reasons = listOf(WhyReason.SelfRegistered, WhyReason.LiveLookup, WhyReason.Reports(count = 12), WhyReason.CarrierVerified),
                modifier = Modifier.padding(vertical = OpenCallerSpacing.m),
            )
        }
    }
}
