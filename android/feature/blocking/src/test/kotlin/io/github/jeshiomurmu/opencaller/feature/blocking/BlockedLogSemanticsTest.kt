package io.github.jeshiomurmu.opencaller.feature.blocking

import android.os.Build
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The blocked-calls log with TalkBack (docs/design.md §11): a heading, each call read as one item that says what
 * happened and why in words, and actions that name the call they act on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class BlockedLogSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = mutableListOf<BlockedLogEvent>()
    private val known = BlockedCall(1, PhoneNumber("+15551230001"), KNOWN, Decision.BLOCK, ScreeningReason.RULE, 0)
    private val hidden = BlockedCall(2, null, null, Decision.SILENCE, ScreeningReason.HIDDEN, 0)

    private fun show(calls: List<BlockedCall>) {
        compose.setContent {
            OpenCallerTheme { BlockedLogScreen(state = BlockedLogUiState(loading = false, calls = calls), onEvent = { events += it }) }
        }
    }

    @Test
    fun eachCallIsOneItemSayingWhatHappenedAndWhy() {
        show(listOf(known, hidden))

        compose.onNodeWithText("Blocked calls").assert(isHeading())
        // The number and its summary are merged into one node ("Blocked · your rule · <time>").
        compose.onNode(hasText(KNOWN) and hasText("Blocked · your rule", substring = true)).assertExists()
        compose.onNode(hasText("Hidden number") and hasText("Silenced · hidden number", substring = true)).assertExists()
    }

    @Test
    fun actionsNameTheCallTheyActOn() {
        show(listOf(known, hidden))

        for (label in listOf("Why $KNOWN was blocked", "Report $KNOWN", "Unblock $KNOWN", "Why Hidden number was blocked")) {
            compose.onNodeWithContentDescription(label).assertHasClickAction().assertHeightIsAtLeast(48.dp)
        }
        // A hidden number can't be reported or unblocked.
        compose.onNode(hasContentDescription("Unblock Hidden number")).assertDoesNotExist()
        compose.onNode(hasContentDescription("Report Hidden number")).assertDoesNotExist()

        compose.onNodeWithContentDescription("Unblock $KNOWN").performClick()
        assertEquals(listOf<BlockedLogEvent>(BlockedLogEvent.Unblock(known)), events)
    }

    @Test
    fun anEmptyLogSaysSo() {
        show(emptyList())

        compose.onNodeWithText("No blocked calls yet", substring = true).assertExists()
    }

    private companion object {
        const val KNOWN = "+1 555-123-0001"
    }
}
