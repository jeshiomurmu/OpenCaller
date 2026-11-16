package io.github.jeshiomurmu.opencaller.microbenchmark

import androidx.benchmark.junit4.BenchmarkRule
import androidx.benchmark.junit4.measureRepeated
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jeshiomurmu.opencaller.core.common.DefaultDispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.SpamListHit
import io.github.jeshiomurmu.opencaller.core.model.BlockRule
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleTarget
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ContactLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.DeviceSources
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ListSources
import io.github.jeshiomurmu.opencaller.core.telephony.screening.NoNetworkLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningCall
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningConfig
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.TimeSource

/**
 * The screening engine's own time for one incoming call (docs/testing.md, Performance), with in-memory sources: the
 * phone's database, contacts provider and network are not part of it (the macro `ScreeningBenchmark` covers the app).
 * Each case first checks it takes the intended path.
 */
@RunWith(AndroidJUnit4::class)
class ScreeningEngineBenchmark {
    @get:Rule
    val benchmarkRule = BenchmarkRule()

    private val normalizer = PhoneNumberNormalizer()
    private val spamNumber = PhoneNumber("+12025550199")
    private val rules =
        (1..RULES).map { i ->
            BlockRule(i.toLong(), RuleType.PREFIX, "+4420$i*", RuleAction.BLOCK, RuleTarget.CALLS, 0, enabled = true)
        } + BlockRule(RULES + 1L, RuleType.PREFIX, "+1900*", RuleAction.BLOCK, RuleTarget.CALLS, 0, enabled = true)

    private val engine =
        ScreeningEngine(
            device =
                DeviceSources(
                    settings = { UserPreferences() },
                    contacts =
                        object : ContactLookup {
                            override fun canRead(): Boolean = true

                            override suspend fun displayName(number: PhoneNumber): String? = null
                        },
                    homeRegion = { "US" },
                    emergencyNumbers = { it == "911" },
                ),
            lists =
                ListSources(
                    rules = { rules },
                    verifiedBusinesses = { null },
                    spamList = { if (it == spamNumber) SpamListHit(it, SPAM_SCORE, "Loan", REPORTS) else null },
                    reputation = { null },
                    network = NoNetworkLookup,
                ),
            normalizer = normalizer,
            dispatchers = DefaultDispatcherProvider,
            timeSource = TimeSource.Monotonic,
            config = ScreeningConfig(),
        )

    private fun measure(
        handle: String,
        expected: ScreeningReason,
    ) {
        val call = ScreeningCall(handle, Direction.INCOMING)
        assertEquals(expected, runBlocking { engine.screen(call) }.reason)
        benchmarkRule.measureRepeated { runBlocking { engine.screen(call) } }
    }

    /** An unknown number that matches nothing: every local step runs. */
    @Test
    fun unknownNumber() = measure("+12025550123", ScreeningReason.NO_MATCH)

    /** A number on the spam list. */
    @Test
    fun spamListNumber() = measure(spamNumber.e164, ScreeningReason.SPAM_LIST)

    /** A number caught by the last of 50 prefix rules. */
    @Test
    fun prefixRule() = measure("+19005550100", ScreeningReason.RULE)

    private companion object {
        const val RULES = 50
        const val SPAM_SCORE = 0.95f
        const val REPORTS = 40
    }
}
