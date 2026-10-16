package io.github.jeshiomurmu.opencaller.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

/**
 * The OpenCaller theme: docs/design.md tokens for color (light/dark), Noto Sans typography and shapes.
 * Dynamic color is off for semantic tokens (design.md §2).
 */
@Composable
fun OpenCallerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalOpenCallerSemanticColors provides if (darkTheme) DarkSemanticColors else LightSemanticColors,
        LocalOpenCallerExtendedTypography provides OpenCallerExtendedTypographyValues,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
            typography = OpenCallerTypography,
            shapes = OpenCallerShapes,
            content = content,
        )
    }
}

/** Access to OpenCaller tokens that Material's theme does not cover. */
object OpenCallerTheme {
    /** Danger, warning, safe, business and gold colors of the current theme. */
    val semanticColors: OpenCallerSemanticColors
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCallerSemanticColors.current

    /** Text styles beyond the Material scale (OTP code). */
    val extendedTypography: OpenCallerExtendedTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalOpenCallerExtendedTypography.current
}
