package io.github.jeshiomurmu.opencaller.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the app's Baseline Profile (`./gradlew :app:generateBaselineProfile`): startup, onboarding, every tab and
 * a screening decision through Protection → Your rules → Test a number. The result is committed to
 * `app/src/main/generated/baselineProfiles` (docs/testing.md, Performance).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() =
        rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
            pressHome()
            startActivityAndWait()
            passOnboardingIfShown()
            visitTabs()
            openNumberTester()
            testNumber("+12025550123", before = testOutcome())
        }
}
