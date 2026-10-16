package io.github.jeshiomurmu.opencaller.core.designsystem.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import io.github.jeshiomurmu.opencaller.core.designsystem.preview.OpenCallerPreviews

// Developer-only previews of the tokens; not shipped UI, so the labels are not string resources.

@Composable
private fun Swatch(
    name: String,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs)) {
        Surface(modifier = Modifier.size(OpenCallerSpacing.l), color = color, shape = OpenCallerShape.chip) {}
        Text(text = name, style = MaterialTheme.typography.bodyMedium)
    }
}

@OpenCallerPreviews
@Composable
private fun ColorTokensPreview() {
    OpenCallerTheme {
        val semantic = OpenCallerTheme.semanticColors
        Column(
            modifier = Modifier.background(MaterialTheme.colorScheme.surface).padding(OpenCallerSpacing.m),
            verticalArrangement = Arrangement.spacedBy(OpenCallerSpacing.xs),
        ) {
            Swatch("primary", MaterialTheme.colorScheme.primary)
            Swatch("onSurfaceVariant", MaterialTheme.colorScheme.onSurfaceVariant)
            Swatch("outline", MaterialTheme.colorScheme.outline)
            Swatch("danger", semantic.danger)
            Swatch("dangerContainer", semantic.dangerContainer)
            Swatch("warning", semantic.warning)
            Swatch("warningContainer", semantic.warningContainer)
            Swatch("safe", semantic.safe)
            Swatch("business", semantic.business)
            Swatch("gold", semantic.gold)
        }
    }
}

@OpenCallerPreviews
@Composable
private fun TypographyPreview() {
    OpenCallerTheme {
        val type = MaterialTheme.typography
        Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface).padding(OpenCallerSpacing.m)) {
            Text("displaySmall 36", style = type.displaySmall)
            Text("headlineMedium 28", style = type.headlineMedium)
            Text("titleLarge 22", style = type.titleLarge)
            Text("titleMedium 16", style = type.titleMedium)
            Text("bodyLarge 16", style = type.bodyLarge)
            Text("bodyMedium 14", style = type.bodyMedium)
            Text("labelLarge 14", style = type.labelLarge)
            Text("labelMedium 12", style = type.labelMedium)
            Text("482 913", style = OpenCallerTheme.extendedTypography.otpCode)
        }
    }
}
