package io.github.jeshiomurmu.opencaller.core.designsystem.preview

import android.content.res.Configuration
import androidx.compose.ui.tooling.preview.Preview

/** Previews every composable in light, dark and at 200 % font scale (docs/rules.md §4, docs/design.md §3). */
@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Preview(name = "Font scale 2x", showBackground = true, fontScale = 2f)
annotation class OpenCallerPreviews
