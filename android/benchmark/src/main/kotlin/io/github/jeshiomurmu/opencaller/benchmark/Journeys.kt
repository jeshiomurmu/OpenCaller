package io.github.jeshiomurmu.opencaller.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** The app under test: release builds have no application id suffix (benchmarkRelease, nonMinifiedRelease). */
internal const val TARGET_PACKAGE = "io.github.jeshiomurmu.opencaller"

/** The number tester's test tags (feature:blocking RulesScreen.kt; keep in step). */
internal const val TEST_NUMBER_TAG = "rules_test_number"
internal const val TEST_RUN_TAG = "rules_test_run"
internal const val TEST_OUTCOME_TAG = "rules_test_outcome"

private const val TIMEOUT_MILLIS = 10_000L
private const val SHORT_WAIT_MILLIS = 3_000L
private const val ONBOARDING_STEPS = 8
private const val SCROLL_PERCENT = 0.5f
private const val DIAGNOSTIC_TEXTS = 40

/** The bottom tabs, in order (app strings tab_*). */
internal val TABS = listOf("Messages", "Contacts", "Protection", "Me", "Calls")

/**
 * Finishes onboarding the way someone who allows nothing would: Get started, Continue past the privacy page, then
 * "Not now" to every request until Finish. Does nothing when the app already shows its tabs.
 */
internal fun MacrobenchmarkScope.passOnboardingIfShown() {
    val start = device.wait(Until.findObject(By.text("Get started")), SHORT_WAIT_MILLIS) ?: return
    start.click()
    device.wait(Until.findObject(By.text("Continue")), TIMEOUT_MILLIS).click()
    repeat(ONBOARDING_STEPS) {
        if (device.hasObject(By.text("Finish"))) return@repeat
        device.wait(Until.findObject(By.text("Not now")), SHORT_WAIT_MILLIS)?.click()
        device.waitForIdle()
    }
    device.wait(Until.findObject(By.text("Finish")), TIMEOUT_MILLIS).click()
    checkNotNull(device.wait(Until.findObject(By.text("Calls")), TIMEOUT_MILLIS)) { "The tabs didn't appear after onboarding" }
}

/** Opens each bottom tab once and waits for it to settle. */
internal fun MacrobenchmarkScope.visitTabs() {
    for (tab in TABS) {
        device.wait(Until.findObject(By.text(tab)), TIMEOUT_MILLIS).click()
        device.waitForIdle()
    }
}

/** Protection → Your rules, scrolled to the number tester; returns its input field. */
internal fun MacrobenchmarkScope.openNumberTester(): UiObject2 {
    device.wait(Until.findObject(By.text("Protection")), TIMEOUT_MILLIS).click()
    device.wait(Until.findObject(By.text("Your rules")), TIMEOUT_MILLIS).click()
    device.wait(Until.findObject(By.res(TEST_NUMBER_TAG)), SHORT_WAIT_MILLIS)?.let { return it }
    val list = device.wait(Until.findObject(By.scrollable(true)), TIMEOUT_MILLIS)
    return checkNotNull(list.scrollUntil(Direction.DOWN, Until.findObject(By.res(TEST_NUMBER_TAG)))) { "No number tester on Your rules" }
}

/**
 * Tests [number] in the number tester and waits for a new outcome (the previous one is [before]). The outcome appears
 * under the field, where the keyboard or the screen's edge can hide it, so the list is scrolled down while waiting.
 */
internal fun MacrobenchmarkScope.testNumber(
    number: String,
    before: String?,
) {
    val field = checkNotNull(device.wait(Until.findObject(By.res(TEST_NUMBER_TAG)), TIMEOUT_MILLIS)) { "No number field" }
    field.text = number
    checkNotNull(device.wait(Until.findObject(By.res(TEST_RUN_TAG).enabled(true)), TIMEOUT_MILLIS)) { "No Test button" }
        .click()
    val changed =
        device.wait(
            {
                val outcome = device.textOf(By.res(TEST_OUTCOME_TAG))
                if (outcome != null && outcome != before) {
                    outcome
                } else {
                    try {
                        device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, SCROLL_PERCENT)
                    } catch (expected: StaleObjectException) {
                        // The list recomposed under us; the next poll finds it again.
                    }
                    null
                }
            },
            TIMEOUT_MILLIS,
        )
    checkNotNull(changed) {
        val shown = device.findObjects(By.textContains("")).mapNotNull { it.textOrNull() }.take(DIAGNOSTIC_TEXTS)
        "The number tester showed no result for $number (field: ${device.textOf(By.res(TEST_NUMBER_TAG))}, " +
            "before: $before, on screen: $shown)"
    }
}

/** The number tester's current outcome text, if any. */
internal fun MacrobenchmarkScope.testOutcome(): String? = device.textOf(By.res(TEST_OUTCOME_TAG))

/**
 * The text of the first node matching [selector], or null. Compose replaces nodes when it recomposes (the outcome
 * text changes while the engine answers), so a node found a moment ago can be stale when its text is read.
 */
private fun UiDevice.textOf(selector: BySelector): String? = findObject(selector)?.textOrNull()

private fun UiObject2.textOrNull(): String? =
    try {
        text
    } catch (expected: StaleObjectException) {
        null
    }
