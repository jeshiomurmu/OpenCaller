package io.github.jeshiomurmu.opencaller.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.SupporterBadge

/**
 * A supporter's badge (docs/prd.md F-SO-06, docs/design.md §2 `gold`): a star and "Supporter" or "Gold supporter",
 * always icon and words. Shown only next to a self-registered name.
 */
@Composable
fun SupporterBadgeLabel(
    badge: SupporterBadge,
    modifier: Modifier = Modifier,
) {
    val color = OpenCallerTheme.semanticColors.gold
    val label = stringResource(badge.labelRes())
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xxs),
    ) {
        Icon(
            painter = painterResource(if (badge == SupporterBadge.GOLD) OpenCallerIcons.StarFilled else OpenCallerIcons.Star),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(OpenCallerSpacing.iconSmall),
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** The badge's label. */
fun SupporterBadge.labelRes(): Int =
    when (this) {
        SupporterBadge.SUPPORTER -> R.string.badge_supporter
        SupporterBadge.GOLD -> R.string.badge_gold
    }

@OpenCallerPreviews
@Composable
private fun SupporterBadgeLabelPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(OpenCallerSpacing.m)) {
                SupporterBadgeLabel(SupporterBadge.SUPPORTER)
                SupporterBadgeLabel(SupporterBadge.GOLD)
            }
        }
    }
}
