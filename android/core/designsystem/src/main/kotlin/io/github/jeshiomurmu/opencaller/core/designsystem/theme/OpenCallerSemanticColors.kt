package io.github.jeshiomurmu.opencaller.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Colors that carry meaning (docs/design.md §1–2): red only for danger, amber only for suspicious, green only
 * for verified/safe, blue only for business, gold for supporters. Always pair them with an icon and a word.
 */
@Immutable
data class OpenCallerSemanticColors(
    val danger: Color,
    val onDanger: Color,
    val dangerContainer: Color,
    val onDangerContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val safe: Color,
    val business: Color,
    val gold: Color,
)

internal val LightSemanticColors =
    OpenCallerSemanticColors(
        danger = LightTokens.Danger,
        onDanger = LightTokens.OnDanger,
        dangerContainer = LightTokens.DangerContainer,
        onDangerContainer = LightTokens.OnDangerContainer,
        warning = LightTokens.Warning,
        warningContainer = LightTokens.WarningContainer,
        safe = LightTokens.Safe,
        business = LightTokens.Business,
        gold = LightTokens.Gold,
    )

// design.md lists no dark onDanger/containers; derive them from dark tokens (fill = token tinted onto surface).
internal val DarkSemanticColors =
    OpenCallerSemanticColors(
        danger = DarkTokens.Danger,
        onDanger = DarkTokens.Background,
        dangerContainer = lerp(DarkTokens.Surface, DarkTokens.Danger, CONTAINER_TINT),
        onDangerContainer = DarkTokens.Danger,
        warning = DarkTokens.Warning,
        warningContainer = lerp(DarkTokens.Surface, DarkTokens.Warning, CONTAINER_TINT),
        safe = DarkTokens.Safe,
        business = DarkTokens.Business,
        gold = DarkTokens.Gold,
    )

/** Semantic colors of the current theme; read through `OpenCallerTheme.semanticColors`. */
val LocalOpenCallerSemanticColors = staticCompositionLocalOf { LightSemanticColors }
