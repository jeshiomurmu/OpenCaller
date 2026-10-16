package io.github.jeshiomurmu.opencaller.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme

/**
 * Full-width amber banner for a degraded state the user can fix, such as "OpenCaller can't screen calls
 * right now" (docs/design.md §8): warning icon, one sentence, one action.
 */
@Composable
fun WarningBanner(
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = OpenCallerTheme.semanticColors
    Surface(modifier = modifier.fillMaxWidth(), color = semantic.warningContainer, contentColor = semantic.warning) {
        Row(
            modifier =
                Modifier.padding(
                    start = OpenCallerSpacing.m,
                    end = OpenCallerSpacing.xs,
                    top = OpenCallerSpacing.xs,
                    bottom = OpenCallerSpacing.xs,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.s),
        ) {
            Icon(painter = painterResource(OpenCallerIcons.Warning), contentDescription = null)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAction) { Text(text = actionLabel) }
        }
    }
}

@OpenCallerPreviews
@Composable
private fun WarningBannerPreview() {
    OpenCallerTheme {
        WarningBanner(
            message = "OpenCaller can't screen calls right now. Turn call screening back on.",
            actionLabel = "Fix",
            onAction = {},
        )
    }
}
