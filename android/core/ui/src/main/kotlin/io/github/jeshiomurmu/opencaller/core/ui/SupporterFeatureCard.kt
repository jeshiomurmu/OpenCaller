package io.github.jeshiomurmu.opencaller.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerSpacing
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme

/**
 * Shown in place of a supporter feature's controls when the user isn't entitled (docs/prd.md F-UT-08), with a way
 * to the supporter screen.
 */
@Composable
fun SupporterFeatureCard(
    onOpenSupporter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs), modifier = Modifier.padding(OpenCallerSpacing.m)) {
            Text(
                text = stringResource(R.string.supporter_feature_title),
                style = MaterialTheme.typography.titleMedium,
                color = OpenCallerTheme.semanticColors.gold,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = stringResource(R.string.supporter_feature_body), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onOpenSupporter) { Text(stringResource(R.string.supporter_feature_open)) }
        }
    }
}

@OpenCallerPreviews
@Composable
private fun SupporterFeatureCardPreview() {
    OpenCallerTheme {
        Surface(color = MaterialTheme.colorScheme.surface) { SupporterFeatureCard(onOpenSupporter = {}) }
    }
}
