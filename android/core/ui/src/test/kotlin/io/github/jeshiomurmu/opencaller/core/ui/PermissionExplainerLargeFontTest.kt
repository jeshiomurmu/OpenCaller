package io.github.jeshiomurmu.opencaller.core.ui

import android.app.Application
import android.os.Build
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.core.designsystem.icon.OpenCallerIcons
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The permission explainer at 200 % font on a small screen (docs/design.md §11): the title, the reasons and both
 * buttons are reachable by scrolling, and nothing is squeezed out.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM], qualifiers = "w320dp-h480dp")
class PermissionExplainerLargeFontTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun show(fontScale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                OpenCallerTheme {
                    PermissionExplainer(
                        icon = OpenCallerIcons.Contacts,
                        title = TITLE,
                        why = WHY,
                        never = NEVER,
                        onContinue = {},
                        onNotNow = {},
                    )
                }
            }
        }
    }

    @Test
    fun everythingIsReachableAtDoubleFont() {
        show(fontScale = 2f)

        compose.onNodeWithText(TITLE).assertIsDisplayed()
        for (text in listOf(WHY, NEVER, context.getString(R.string.explainer_continue), context.getString(R.string.explainer_not_now))) {
            compose.onNodeWithText(text).performScrollTo().assertIsDisplayed()
        }
        // Scrolling back brings the title into view again.
        compose.onNodeWithText(TITLE).performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp")
    fun atNormalSizeTheTitleAndButtonsShowWithoutScrolling() {
        show(fontScale = 1f)

        compose.onNodeWithText(TITLE).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.explainer_continue)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.explainer_not_now)).assertIsDisplayed()
    }

    private companion object {
        const val TITLE = "Show your contacts"
        const val WHY = "OpenCaller shows your own names for the numbers in your contacts, on this phone."
        const val NEVER = "Your contacts never leave this phone."
    }
}
