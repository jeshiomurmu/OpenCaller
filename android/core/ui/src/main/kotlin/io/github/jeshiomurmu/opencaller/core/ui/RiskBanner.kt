package io.github.jeshiomurmu.opencaller.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel

/**
 * Risk line such as "Likely scam · Loan · 214 reports": red for [RiskLevel.LIKELY_SPAM], amber for
 * [RiskLevel.SUSPICIOUS], always with an icon and words (never color alone). Shows nothing for [RiskLevel.NONE].
 */
@Composable
fun RiskBanner(
    level: RiskLevel,
    modifier: Modifier = Modifier,
    category: String? = null,
    reportCount: Int = 0,
) {
    if (level == RiskLevel.NONE) return
    val semantic = OpenCallerTheme.semanticColors
    val isSpam = level == RiskLevel.LIKELY_SPAM
    val parts =
        buildList {
            add(stringResource(if (isSpam) R.string.risk_likely_scam else R.string.risk_suspicious))
            category?.takeIf { it.isNotBlank() }?.let(::add)
            if (reportCount > 0) add(pluralStringResource(R.plurals.risk_report_count, reportCount, reportCount))
        }
    Surface(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        shape = OpenCallerShape.chip,
        color = if (isSpam) semantic.dangerContainer else semantic.warningContainer,
        contentColor = if (isSpam) semantic.onDangerContainer else semantic.warning,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = OpenCallerSpacing.s, vertical = OpenCallerSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
        ) {
            Icon(painter = painterResource(if (isSpam) OpenCallerIcons.Danger else OpenCallerIcons.Warning), contentDescription = null)
            Text(text = parts.joinToString(stringResource(R.string.risk_banner_separator)), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OpenCallerPreviews
@Composable
private fun RiskBannerPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(OpenCallerSpacing.m), verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
                RiskBanner(level = RiskLevel.LIKELY_SPAM, category = "Loan", reportCount = 214)
                RiskBanner(level = RiskLevel.SUSPICIOUS, reportCount = 1)
            }
        }
    }
}
