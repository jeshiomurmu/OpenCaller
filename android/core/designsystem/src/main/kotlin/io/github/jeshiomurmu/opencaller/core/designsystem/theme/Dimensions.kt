package io.github.jeshiomurmu.opencaller.core.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale from docs/design.md §4 (dp). */
object OpenCallerSpacing {
    /** 4 dp. */
    val xxs: Dp = 4.dp

    /** 8 dp. */
    val xs: Dp = 8.dp

    /** 12 dp. */
    val s: Dp = 12.dp

    /** 16 dp. */
    val m: Dp = 16.dp

    /** 24 dp. */
    val l: Dp = 24.dp

    /** 32 dp. */
    val xl: Dp = 32.dp

    /** 48 dp. */
    val xxl: Dp = 48.dp

    /** Horizontal padding of every screen. */
    val screenPadding: Dp = m

    /** Minimum touch target size. */
    val minTouchTarget: Dp = 48.dp

    /** Size of icons in empty states and large affordances. */
    val iconLarge: Dp = 48.dp

    /** Size of icons inside chips and badges. */
    val iconSmall: Dp = 18.dp
}

/** Corner shapes from docs/design.md §4. */
object OpenCallerShape {
    /** Chips: 8 dp. */
    val chip: Shape = RoundedCornerShape(8.dp)

    /** Cards: 16 dp. */
    val card: Shape = RoundedCornerShape(16.dp)

    /** Bottom sheets: 28 dp top corners. */
    val sheet: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** Buttons: full pill. */
    val pill: Shape = CircleShape
}

/** Material shape roles: small = chips (8), medium/large = cards (16), extra large = sheets (28). */
internal val OpenCallerShapes =
    Shapes(
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
