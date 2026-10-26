package io.github.jeshiomurmu.opencaller.onboarding

import android.app.role.RoleManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.fail
import org.junit.AssumptionViolatedException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern
import kotlin.math.abs

/** UiAutomator helpers for driving OpenCaller and the system's role and permission dialogs. */
object Ui {
    private const val TIMEOUT_MILLIS = 10_000L
    private const val MAX_DUMP_CHARS = 20_000

    val device: UiDevice by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Role dialog's decline button: AlertDialog button on API 29, matched by text on newer PermissionControllers. */
    val roleDecline: List<BySelector> = listOf(By.res("android", "button2"), By.text(Pattern.compile("(?i)cancel")))

    /** Runtime permission dialog's deny button (com.android.permissioncontroller or Google's build of it). */
    val permissionDeny: List<BySelector> =
        listOf(By.res(Pattern.compile(".*:id/permission_deny_button")), By.text(Pattern.compile("(?i)(deny|don.t allow)")))

    fun string(
        @StringRes id: Int,
        vararg args: Any,
    ): String = context.getString(id, *args)

    /** Waits for an on-screen node with exactly [text]. */
    fun waitFor(text: String): UiObject2 =
        device.wait(Until.findObject(By.text(text)), TIMEOUT_MILLIS) ?: throw AssertionError("'$text' is not on screen")

    /** Taps the node with exactly [text]. */
    fun tap(text: String) {
        waitFor(text).click()
    }

    /** True when a node with exactly [text] shows up within [timeoutMillis]. */
    fun isShown(
        text: String,
        timeoutMillis: Long = TIMEOUT_MILLIS,
    ): Boolean = device.wait(Until.hasObject(By.text(text)), timeoutMillis) == true

    /** Scrolls the first scrollable container until [text] appears, then returns it. */
    fun scrollTo(text: String): UiObject2 {
        val found =
            device.findObject(By.text(text))
                ?: device
                    .wait(Until.findObject(By.scrollable(true)), TIMEOUT_MILLIS)
                    ?.scrollUntil(Direction.DOWN, Until.findObject(By.text(text)))
        return found ?: waitFor(text)
    }

    /**
     * Taps the [button] on the same row as [label] (several rows can have a button with the same text, e.g. "Set up"
     * on the onboarding Extras step).
     */
    fun tapInRowOf(
        label: String,
        button: String,
    ) {
        val row = scrollTo(label).visibleBounds
        val candidates = device.findObjects(By.text(button))
        val target =
            candidates.minByOrNull { abs(it.visibleBounds.centerY() - row.centerY()) }
                ?: throw AssertionError("'$button' is not on screen")
        target.click()
    }

    /**
     * Scrolls to [text] and taps it. Rows above it can still grow while the screen loads and push it out of view, so
     * it is looked up again after the screen settles, right before the tap.
     */
    fun scrollToAndTap(text: String) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            scrollTo(text)
            device.waitForIdle()
            val row = device.findObject(By.text(text))
            if (row != null) {
                row.click()
                return
            }
        }
        throw AssertionError("'$text' is not on screen")
    }

    /**
     * Taps the first of [selectors] to appear (system dialogs differ between Android versions) and waits until it is
     * gone. A tap that lands while the dialog is still animating in is ignored by the system (seen on API 36), so the
     * tap is repeated until the button disappears.
     */
    fun tapSystemButton(selectors: List<BySelector>) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        var tapped = false
        while (System.currentTimeMillis() < deadline) {
            val selector = selectors.firstOrNull { device.hasObject(it) }
            if (selector == null) {
                if (tapped) return
                Thread.sleep(POLL_MILLIS)
                continue
            }
            device.waitForIdle()
            device.findObject(selector)?.click()
            tapped = true
            device.wait(Until.gone(selector), TAP_SETTLE_MILLIS)
        }
        if (tapped) fail("$selectors still on screen after tapping") else fail("none of $selectors appeared")
    }

    /** Runs [command] as the shell user and returns its output. */
    fun shell(command: String): String =
        InstrumentationRegistry
            .getInstrumentation()
            .uiAutomation
            .executeShellCommand(command)
            .let { fd ->
                android.os.ParcelFileDescriptor
                    .AutoCloseInputStream(fd)
                    .use { it.readBytes().decodeToString() }
            }

    /** Takes the call screening role away (shell `cmd role`), so each test starts without it. */
    fun removeCallScreeningRole() {
        shell("cmd role remove-role-holder ${RoleManager.ROLE_CALL_SCREENING} ${context.packageName}")
    }

    fun holdsCallScreeningRole(): Boolean = context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)

    /** Waits until another app (e.g. a system settings page) is in front. */
    fun waitUntilAppLeavesForeground() {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (device.currentPackageName == context.packageName && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MILLIS)
        device.waitForIdle()
    }

    fun windowDump(): String {
        val out = ByteArrayOutputStream()
        device.dumpWindowHierarchy(out)
        return out.toString(Charsets.UTF_8.name()).take(MAX_DUMP_CHARS)
    }

    private const val POLL_MILLIS = 250L
    private const val TAP_SETTLE_MILLIS = 2_000L
}

/**
 * Adds the on-screen hierarchy to any failure (taken before [cleanUp] closes the app), so CI logs show what was on
 * screen. Skipped tests (assumptions) pass through unchanged.
 */
class DumpWindowOnFailure(
    private val cleanUp: () -> Unit,
) : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (e: AssumptionViolatedException) {
                    throw e
                } catch (e: Throwable) {
                    throw AssertionError("${e.message}\n--- window hierarchy ---\n${Ui.windowDump()}", e)
                } finally {
                    cleanUp()
                }
            }
        }
}
