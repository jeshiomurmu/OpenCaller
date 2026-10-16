package io.github.jeshiomurmu.opencaller.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.jeshiomurmu.opencaller.core.designsystem.R

/** Noto Sans (SIL OFL 1.1), bundled for Latin scripts (docs/design.md §3). */
val NotoSans =
    FontFamily(
        Font(R.font.noto_sans_regular, FontWeight.Normal),
        Font(R.font.noto_sans_medium, FontWeight.Medium),
    )

/** Noto Sans Mono (SIL OFL 1.1) for OTP codes and dialled numbers. */
val NotoSansMono = FontFamily(Font(R.font.noto_sans_mono_semibold, FontWeight.SemiBold))

private val base = Typography()

/** Material type scale per docs/design.md §3, in Noto Sans; no style is below 12 sp. */
internal val OpenCallerTypography =
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = NotoSans),
        displayMedium = base.displayMedium.copy(fontFamily = NotoSans),
        displaySmall = base.displaySmall.copy(fontFamily = NotoSans, fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 44.sp),
        headlineLarge = base.headlineLarge.copy(fontFamily = NotoSans),
        headlineMedium =
            base.headlineMedium.copy(
                fontFamily = NotoSans,
                fontWeight = FontWeight.Medium,
                fontSize = 28.sp,
                lineHeight = 36.sp,
            ),
        headlineSmall = base.headlineSmall.copy(fontFamily = NotoSans),
        titleLarge = base.titleLarge.copy(fontFamily = NotoSans, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium = base.titleMedium.copy(fontFamily = NotoSans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
        titleSmall = base.titleSmall.copy(fontFamily = NotoSans),
        bodyLarge = base.bodyLarge.copy(fontFamily = NotoSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(fontFamily = NotoSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
        bodySmall = base.bodySmall.copy(fontFamily = NotoSans, fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = base.labelLarge.copy(fontFamily = NotoSans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = base.labelMedium.copy(fontFamily = NotoSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
        labelSmall = base.labelSmall.copy(fontFamily = NotoSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    )

/** Text styles beyond the Material scale (docs/design.md §3). */
@Immutable
data class OpenCallerExtendedTypography(
    /** OTP codes: 32/40 sp Noto Sans Mono SemiBold. */
    val otpCode: TextStyle,
)

internal val OpenCallerExtendedTypographyValues =
    OpenCallerExtendedTypography(
        otpCode = TextStyle(fontFamily = NotoSansMono, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    )

/** Extended text styles of the current theme; read through `OpenCallerTheme.extendedTypography`. */
val LocalOpenCallerExtendedTypography = staticCompositionLocalOf { OpenCallerExtendedTypographyValues }
