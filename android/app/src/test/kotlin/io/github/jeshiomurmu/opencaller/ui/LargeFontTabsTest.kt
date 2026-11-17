package io.github.jeshiomurmu.opencaller.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import io.github.jeshiomurmu.opencaller.R
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.AccessItemStatus
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Calls and Messages tabs at 200 % font (docs/design.md §11) on a small screen: the default-app card and the
 * placeholder below it stay reachable by scrolling. Before Phase 14's fix the card squeezed the placeholder to nothing
 * (the emulator walk didn't find it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM], application = Application::class, qualifiers = "w320dp-h480dp")
class LargeFontTabsTest {
    private val compose = createComposeRule()

    /**
     * Robolectric reads an application module's own manifest, where the Compose rule's host activity isn't declared
     * (`ui-test-manifest` only reaches library modules' test manifests), so the test registers it.
     */
    private val composeHost =
        object : ExternalResource() {
            override fun before() {
                val app = ApplicationProvider.getApplicationContext<Application>()
                val host = ComponentName(app, ComponentActivity::class.java)
                val launcher = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                shadowOf(app.packageManager).apply {
                    addActivityIfNotPresent(host)
                    addIntentFilterForActivity(host, launcher)
                }
            }
        }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(composeHost).around(compose)

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun showAtDoubleFont(content: @Composable () -> Unit) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                OpenCallerTheme { content() }
            }
        }
    }

    @Test
    fun callsTabScrollsToTheRoleButtonAndThePlaceholder() {
        showAtDoubleFont { CallsScreen(defaultPhoneApp = AccessItemStatus.NOT_GRANTED, onSetDefault = {}) }

        compose.onNodeWithText(context.getString(R.string.calls_default_app_action)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.placeholder_calls)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun messagesTabScrollsToThePlaceholder() {
        showAtDoubleFont { MessagesScreen(defaultSmsApp = AccessItemStatus.NOT_GRANTED, onSetDefault = {}) }

        compose.onNodeWithText(context.getString(R.string.placeholder_messages)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun withoutTheCardThePlaceholderIsShownWithoutScrolling() {
        showAtDoubleFont { CallsScreen(defaultPhoneApp = null, onSetDefault = {}) }

        compose.onNodeWithText(context.getString(R.string.placeholder_calls)).assertIsDisplayed()
    }
}
