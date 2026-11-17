package io.github.jeshiomurmu.opencaller.feature.otp

import android.os.Build
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The OTP guard with TalkBack (docs/design.md §11): announced the moment it appears, a heading, who sent the code in
 * words, and two large buttons.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class OtpGuardScreenSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    private var hangUps = 0
    private var closes = 0

    private fun show(state: OtpGuardUiState) {
        compose.setContent {
            OpenCallerTheme { OtpGuardScreen(state = state, onHangUp = { hangUps++ }, onClose = { closes++ }) }
        }
    }

    @Test
    fun theWarningIsAnnouncedAsAPaneWithAnAssertiveHeading() {
        show(OtpGuardUiState(codeFrom = "Northpeak Bank", canHangUp = true))

        compose
            .onNodeWithText(TITLE)
            .assert(isHeading())
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle, TITLE)).assertExists()
        compose.onNodeWithText("The code is from Northpeak Bank.").assertExists()
    }

    @Test
    fun bothActionsAreLargeLabelledButtons() {
        show(OtpGuardUiState(codeFrom = "Northpeak Bank", canHangUp = true))

        for (label in listOf("Hang up", "I understand")) {
            compose
                .onNodeWithText(label)
                .assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assertHeightIsAtLeast(48.dp)
        }
        compose.onNodeWithText("Hang up").performClick()
        compose.onNodeWithText("I understand").performClick()
        assertEquals(1, hangUps)
        assertEquals(1, closes)
    }

    @Test
    fun withoutTheDefaultPhoneAppRoleOnlyCloseIsOffered() {
        show(OtpGuardUiState(codeFrom = "Example Flash", flashCall = true))

        compose.onNodeWithText("Hang up").assertDoesNotExist()
        compose.onNodeWithText("I understand").assertHasClickAction()
        compose.onNodeWithText("The code came as a missed call from Example Flash.").assertExists()
    }

    private companion object {
        const val TITLE = "Don't share this code"
    }
}
