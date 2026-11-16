package io.github.jeshiomurmu.opencaller.core.ui

import android.os.Build
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.SpamVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What TalkBack meets on the caller card (docs/design.md §11): who is calling in one stop, the risk in words, and the
 * card's actions within three swipes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class CallerCardSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    private val number = PhoneNumber("+12015550123")

    private fun show(caller: CallerInfo) {
        compose.setContent {
            OpenCallerTheme {
                CallerCard(caller = caller, formattedNumber = FORMATTED) {
                    Button(onClick = {}) { Text(BLOCK) }
                    Button(onClick = {}) { Text(CLOSE) }
                }
            }
        }
    }

    @Test
    fun spamCallSaysTheRiskInWordsAndTheActionsComeWithinThreeSwipes() {
        show(CallerInfo(number = number, spamVerdict = SpamVerdict(0.95f, RiskLevel.LIKELY_SPAM, "Loan", 214)))

        val stops = talkBackStops()
        val block = stops.indexOfFirst { BLOCK in it }
        assertTrue("Block is stop ${block + 1} of $stops", block in 0..2)
        assertEquals(block + 1, stops.indexOfFirst { CLOSE in it })
        val risk = stops.single { "Likely scam" in it }
        assertTrue(risk, "Loan" in risk && "214 reports" in risk)
        assertTrue(stops.first().contains(FORMATTED))
    }

    @Test
    fun aContactIsOneStopWithItsNameNumberAndSource() {
        show(CallerInfo(number = number, displayName = "Ada Lovelace", nameSource = NameSource.CONTACT))

        val stops = talkBackStops()
        val who = stops.first()
        assertTrue(who, "Ada Lovelace" in who && FORMATTED in who && "Contact" in who)
        assertEquals(BLOCK, stops[1])
    }

    @Test
    fun aHiddenCallerIsNamedAsSuch() {
        show(CallerInfo(number = null))

        val who = talkBackStops().first()
        assertTrue(who, "Hidden number" in who && "The caller hid their number." in who)
    }

    // The nodes TalkBack stops at, in order, each as the text it reads (merged tree, depth first).
    private fun talkBackStops(): List<String> {
        val stops = mutableListOf<String>()

        fun visit(node: SemanticsNode) {
            val config = node.config
            val spoken =
                config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                    config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            if (spoken.isNotEmpty() || config.getOrNull(SemanticsActions.OnClick) != null) stops += spoken.joinToString(" ")
            node.children.forEach(::visit)
        }
        visit(compose.onRoot().fetchSemanticsNode())
        return stops
    }

    private companion object {
        const val FORMATTED = "(201) 555-0123"
        const val BLOCK = "Block"
        const val CLOSE = "Close"
    }
}
