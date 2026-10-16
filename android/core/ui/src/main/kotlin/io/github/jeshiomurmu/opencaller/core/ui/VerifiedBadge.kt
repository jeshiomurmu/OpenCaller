package io.github.jeshiomurmu.opencaller.core.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme

/**
 * Blue check + "Verified business" (docs/design.md §5). When [onShowDetails] is set the badge is a 48 dp
 * touch target that opens the explanation of how the business was verified.
 */
@Composable
fun VerifiedBadge(
    modifier: Modifier = Modifier,
    onShowDetails: (() -> Unit)? = null,
) {
    val color = OpenCallerTheme.semanticColors.business
    val description =
        stringResource(if (onShowDetails == null) R.string.verified_business else R.string.verified_business_explain)
    val interaction =
        if (onShowDetails == null) {
            Modifier
        } else {
            Modifier.minimumInteractiveComponentSize().clickable(role = Role.Button, onClick = onShowDetails)
        }
    Row(
        modifier = modifier.then(interaction).clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xxs),
    ) {
        Icon(
            painter = painterResource(OpenCallerIcons.Verified),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(OpenCallerSpacing.iconSmall),
        )
        Text(text = stringResource(R.string.verified_business), style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@OpenCallerPreviews
@Composable
private fun VerifiedBadgePreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.padding(OpenCallerSpacing.m)) {
                VerifiedBadge()
                VerifiedBadge(onShowDetails = {})
            }
        }
    }
}
