package io.github.jeshiomurmu.opencaller.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One screening decision in the running app: the engine's trace section (`OpenCaller.screen`, ScreeningTrace in
 * core:telephony) around a number tested through Protection → Your rules → Test a number. The release build has no
 * server, so the decision covers the local steps and a failed live lookup, as on a phone offline.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class ScreeningBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun screenANumber() {
        var before: String? = null
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(TraceSectionMetric(SECTION, TraceSectionMetric.Mode.Sum)),
            compilationMode = CompilationMode.Partial(),
            startupMode = null,
            iterations = ITERATIONS,
            setupBlock = {
                if (!device.hasObject(By.res(TEST_NUMBER_TAG))) {
                    startActivityAndWait()
                    passOnboardingIfShown()
                    openNumberTester()
                }
                before = testOutcome()
            },
        ) {
            // A different number each time (555-0100… is the fictional range), so a new outcome replaces the last one.
            testNumber("+1202555%04d".format(FIRST_NUMBER + (iteration ?: 0)), before)
        }
    }

    private companion object {
        const val SECTION = "OpenCaller.screen"
        const val ITERATIONS = 20
        const val FIRST_NUMBER = 100
    }
}
