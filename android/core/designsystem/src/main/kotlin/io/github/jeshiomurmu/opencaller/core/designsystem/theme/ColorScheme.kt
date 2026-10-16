package io.github.jeshiomurmu.opencaller.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.lerp

/** Tint for container fills derived from a token (token blended onto the surface). */
internal const val CONTAINER_TINT = 0.16f

// Tonal steps for surface containers (onSurface blended onto surface); docs/design.md §4 prefers tonal surfaces.
private const val SURFACE_STEP = 0.03f
private const val OUTLINE_VARIANT = 0.4f

/**
 * Material color roles mapped onto the docs/design.md §2 tokens. Roles design.md does not define (containers,
 * surface tones) are blends of those tokens, so no color outside the token set is introduced.
 */
internal val LightColorScheme: ColorScheme =
    lightColorScheme(
        primary = LightTokens.Primary,
        onPrimary = LightTokens.OnPrimary,
        primaryContainer = lerp(LightTokens.Surface, LightTokens.Primary, CONTAINER_TINT),
        onPrimaryContainer = LightTokens.OnSurface,
        inversePrimary = DarkTokens.Primary,
        secondary = LightTokens.Primary,
        onSecondary = LightTokens.OnPrimary,
        secondaryContainer = lerp(LightTokens.Surface, LightTokens.Primary, CONTAINER_TINT),
        onSecondaryContainer = LightTokens.OnSurface,
        tertiary = LightTokens.Business,
        onTertiary = LightTokens.OnPrimary,
        tertiaryContainer = lerp(LightTokens.Surface, LightTokens.Business, CONTAINER_TINT),
        onTertiaryContainer = LightTokens.OnSurface,
        background = LightTokens.Surface,
        onBackground = LightTokens.OnSurface,
        surface = LightTokens.Surface,
        onSurface = LightTokens.OnSurface,
        surfaceVariant = lerp(LightTokens.Surface, LightTokens.OnSurface, SURFACE_STEP * 2),
        onSurfaceVariant = LightTokens.OnSurfaceVariant,
        surfaceTint = LightTokens.Primary,
        inverseSurface = LightTokens.OnSurface,
        inverseOnSurface = LightTokens.Surface,
        error = LightTokens.Danger,
        onError = LightTokens.OnDanger,
        errorContainer = LightTokens.DangerContainer,
        onErrorContainer = LightTokens.OnDangerContainer,
        outline = LightTokens.Outline,
        outlineVariant = lerp(LightTokens.Surface, LightTokens.Outline, OUTLINE_VARIANT),
        scrim = LightTokens.OnSurface,
        surfaceBright = LightTokens.Surface,
        surfaceDim = lerp(LightTokens.Surface, LightTokens.OnSurface, SURFACE_STEP * 3),
        surfaceContainerLowest = LightTokens.Surface,
        surfaceContainerLow = lerp(LightTokens.Surface, LightTokens.OnSurface, SURFACE_STEP / 2),
        surfaceContainer = lerp(LightTokens.Surface, LightTokens.OnSurface, SURFACE_STEP),
        surfaceContainerHigh = lerp(LightTokens.Surface, LightTokens.OnSurface, SURFACE_STEP * 2),
        surfaceContainerHighest = lerp(LightTokens.Surface, LightTokens.OnSurface, SURFACE_STEP * 3),
    )

/** Dark counterpart of [LightColorScheme]. */
internal val DarkColorScheme: ColorScheme =
    darkColorScheme(
        primary = DarkTokens.Primary,
        onPrimary = DarkTokens.OnPrimary,
        primaryContainer = lerp(DarkTokens.Surface, DarkTokens.Primary, CONTAINER_TINT),
        onPrimaryContainer = DarkTokens.OnSurface,
        inversePrimary = LightTokens.Primary,
        secondary = DarkTokens.Primary,
        onSecondary = DarkTokens.OnPrimary,
        secondaryContainer = lerp(DarkTokens.Surface, DarkTokens.Primary, CONTAINER_TINT),
        onSecondaryContainer = DarkTokens.OnSurface,
        tertiary = DarkTokens.Business,
        onTertiary = DarkTokens.Background,
        tertiaryContainer = lerp(DarkTokens.Surface, DarkTokens.Business, CONTAINER_TINT),
        onTertiaryContainer = DarkTokens.OnSurface,
        background = DarkTokens.Background,
        onBackground = DarkTokens.OnSurface,
        surface = DarkTokens.Surface,
        onSurface = DarkTokens.OnSurface,
        surfaceVariant = lerp(DarkTokens.Surface, DarkTokens.OnSurface, SURFACE_STEP * 2),
        onSurfaceVariant = DarkTokens.OnSurfaceVariant,
        surfaceTint = DarkTokens.Primary,
        inverseSurface = DarkTokens.OnSurface,
        inverseOnSurface = DarkTokens.Surface,
        error = DarkTokens.Danger,
        onError = DarkTokens.Background,
        errorContainer = DarkSemanticColors.dangerContainer,
        onErrorContainer = DarkSemanticColors.onDangerContainer,
        outline = DarkTokens.Outline,
        outlineVariant = lerp(DarkTokens.Surface, DarkTokens.Outline, OUTLINE_VARIANT),
        scrim = DarkTokens.Background,
        surfaceBright = lerp(DarkTokens.Surface, DarkTokens.OnSurface, SURFACE_STEP * 3),
        surfaceDim = DarkTokens.Background,
        surfaceContainerLowest = DarkTokens.Background,
        surfaceContainerLow = DarkTokens.Surface,
        surfaceContainer = lerp(DarkTokens.Surface, DarkTokens.OnSurface, SURFACE_STEP),
        surfaceContainerHigh = lerp(DarkTokens.Surface, DarkTokens.OnSurface, SURFACE_STEP * 2),
        surfaceContainerHighest = lerp(DarkTokens.Surface, DarkTokens.OnSurface, SURFACE_STEP * 3),
    )
