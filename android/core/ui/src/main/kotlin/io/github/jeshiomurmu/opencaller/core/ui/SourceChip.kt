package io.github.jeshiomurmu.opencaller.core.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
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
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerShape
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.NameSource

/** Shows where a name came from: icon + label (docs/design.md §5). Green for contacts, blue for verified businesses. */
@Composable
fun SourceChip(
    source: NameSource,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(source.labelRes())
    val color =
        when (source) {
            NameSource.CONTACT -> OpenCallerTheme.semanticColors.safe
            NameSource.VERIFIED_BUSINESS -> OpenCallerTheme.semanticColors.business
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
        shape = OpenCallerShape.chip,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = color,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = OpenCallerSpacing.xs, vertical = OpenCallerSpacing.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xxs),
        ) {
            Icon(
                painter = painterResource(source.iconRes()),
                contentDescription = null,
                modifier = Modifier.size(OpenCallerSpacing.iconSmall),
            )
            Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

/** Label of a name source. */
@StringRes
internal fun NameSource.labelRes(): Int =
    when (this) {
        NameSource.CONTACT -> R.string.source_contact
        NameSource.SELF_REGISTERED -> R.string.source_self_registered
        NameSource.VERIFIED_BUSINESS -> R.string.source_verified_business
        NameSource.SENDER_REGISTRY -> R.string.source_sender_registry
        NameSource.COMMUNITY_LABEL -> R.string.source_community_label
        NameSource.DEVICE_INFERENCE -> R.string.source_device_inference
    }

/** Icon of a name source. */
@DrawableRes
internal fun NameSource.iconRes(): Int =
    when (this) {
        NameSource.CONTACT -> OpenCallerIcons.Contacts
        NameSource.SELF_REGISTERED -> OpenCallerIcons.SelfRegistered
        NameSource.VERIFIED_BUSINESS -> OpenCallerIcons.Verified
        NameSource.SENDER_REGISTRY -> OpenCallerIcons.SenderRegistry
        NameSource.COMMUNITY_LABEL -> OpenCallerIcons.Community
        NameSource.DEVICE_INFERENCE -> OpenCallerIcons.Device
    }

@OpenCallerPreviews
@Composable
private fun SourceChipPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(OpenCallerSpacing.m), verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
                NameSource.entries.forEach { SourceChip(source = it) }
            }
        }
    }
}
