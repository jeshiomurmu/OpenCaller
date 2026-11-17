package io.github.jeshiomurmu.opencaller.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Cold start to the first frame of the tabs (onboarding is finished once in the setup), without compilation and with
 * the Baseline Profile. Run after `:app:generateBaselineProfile`; [BaselineProfileMode.Require] fails without one.
 */
@RunWith(Parameterized::class)
class StartupBenchmark(
    private val compilation: CompilationMode,
) {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStart() =
        rule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilation,
            startupMode = StartupMode.COLD,
            iterations = ITERATIONS,
            setupBlock = {
                // The process is killed between this block and the measured start.
                pressHome()
                startActivityAndWait()
                passOnboardingIfShown()
                pressHome()
            },
        ) {
            startActivityAndWait()
        }

    /** The compilation modes compared. */
    companion object {
        private const val ITERATIONS = 10

        /** No compilation, then the Baseline Profile. */
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes(): List<CompilationMode> = listOf(CompilationMode.None(), CompilationMode.Partial(BaselineProfileMode.Require))
    }
}
