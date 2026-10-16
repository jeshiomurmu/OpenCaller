package io.github.jeshiomurmu.opencaller.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** WCAG 2.x contrast of every text/background pair the theme defines (docs/design.md §2). */
class ColorContrastTest {
    @Test
    fun lightTextPairsMeetAa() {
        val scheme = LightColorScheme
        val semantic = LightSemanticColors
        assertAa("onSurface/surface", scheme.onSurface, scheme.surface)
        assertAa("onSurfaceVariant/surface", scheme.onSurfaceVariant, scheme.surface)
        assertAa("onPrimary/primary", scheme.onPrimary, scheme.primary)
        assertAa("primary/surface", scheme.primary, scheme.surface)
        assertAa("onSecondaryContainer/secondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer)
        assertAa("onSurface/surfaceContainer", scheme.onSurface, scheme.surfaceContainer)
        assertSemantic(semantic, scheme.surface)
        assertUi("outline/surface", scheme.outline, scheme.surface)
    }

    @Test
    fun darkTextPairsMeetAa() {
        val scheme = DarkColorScheme
        val semantic = DarkSemanticColors
        assertAa("onSurface/surface", scheme.onSurface, scheme.surface)
        assertAa("onSurface/background", scheme.onBackground, scheme.background)
        assertAa("onSurfaceVariant/surface", scheme.onSurfaceVariant, scheme.surface)
        assertAa("onPrimary/primary", scheme.onPrimary, scheme.primary)
        assertAa("primary/surface", scheme.primary, scheme.surface)
        assertAa("onSecondaryContainer/secondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer)
        assertAa("onSurface/surfaceContainer", scheme.onSurface, scheme.surfaceContainer)
        assertSemantic(semantic, scheme.surface)
        assertUi("outline/surface", scheme.outline, scheme.surface)
    }

    @Test
    fun tokensMatchDesignDocument() {
        assertTrue(LightColorScheme.primary == Color(0xFF0B6E69))
        assertTrue(DarkColorScheme.primary == Color(0xFF5FD4CB))
        assertTrue(LightColorScheme.error == LightSemanticColors.danger)
        assertTrue(DarkColorScheme.background == Color(0xFF111314))
    }

    private fun assertSemantic(
        semantic: OpenCallerSemanticColors,
        surface: Color,
    ) {
        assertAa("danger/surface", semantic.danger, surface)
        assertAa("onDanger/danger", semantic.onDanger, semantic.danger)
        assertAa("onDangerContainer/dangerContainer", semantic.onDangerContainer, semantic.dangerContainer)
        assertAa("warning/warningContainer", semantic.warning, semantic.warningContainer)
        assertAa("warning/surface", semantic.warning, surface)
        assertAa("safe/surface", semantic.safe, surface)
        assertAa("business/surface", semantic.business, surface)
        assertAa("gold/surface", semantic.gold, surface)
    }

    private fun assertAa(
        name: String,
        foreground: Color,
        background: Color,
    ) {
        val ratio = contrast(foreground, background)
        assertTrue("$name contrast $ratio < 4.5", ratio >= TEXT_AA)
    }

    private fun assertUi(
        name: String,
        foreground: Color,
        background: Color,
    ) {
        val ratio = contrast(foreground, background)
        assertTrue("$name contrast $ratio < 3", ratio >= UI_AA)
    }

    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun luminance(color: Color): Double {
        fun channel(c: Float): Double = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }

    private companion object {
        const val TEXT_AA = 4.5
        const val UI_AA = 3.0
    }
}
