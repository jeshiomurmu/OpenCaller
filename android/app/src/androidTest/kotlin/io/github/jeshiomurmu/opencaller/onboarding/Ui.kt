package io.github.jeshiomurmu.opencaller.onboarding

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.fail
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern

/** UiAutomator helpers for driving OpenCaller and the system's role and permission dialogs. */
internal object Ui {
    private const val TIMEOUT_MILLIS = 10_000L
    private const val MAX_DUMP_CHARS = 20_000

    val device: UiDevice by lazy { UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()) }
    val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Role dialog: AlertDialog buttons on API 29, matched by text as a fallback on newer PermissionControllers. */
    val roleAccept: List<BySelector> = listOf(By.res("android", "button1"), By.text(Pattern.compile("(?i)set as default")))
    val roleDecline: List<BySelector> = listOf(By.res("android", "button2"), By.text(Pattern.compile("(?i)cancel")))

    /** Runtime permission dialog (com.android.permissioncontroller or Google's build of it). */
    val permissionAllow: List<BySelector> =
        listOf(By.res(Pattern.compile(".*:id/permission_allow(_foreground_only)?_button")), By.text(Pattern.compile("(?i)allow")))
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

    /** Taps the first of [selectors] to appear (system dialogs differ between Android versions). */
    fun tapSystemButton(selectors: List<BySelector>) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            selectors.firstNotNullOfOrNull { device.findObject(it) }?.let {
                it.click()
                return
            }
            Thread.sleep(POLL_MILLIS)
        }
        fail("none of $selectors appeared")
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

    fun setCallScreeningRole(held: Boolean) {
        val action = if (held) "add-role-holder" else "remove-role-holder"
        shell("cmd role $action ${RoleManager.ROLE_CALL_SCREENING} ${context.packageName}")
    }

    fun holdsCallScreeningRole(): Boolean = context.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_CALL_SCREENING)

    /** Brings OpenCaller back to the front the way the launcher does. */
    fun relaunchFromLauncher() {
        val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun windowDump(): String {
        val out = ByteArrayOutputStream()
        device.dumpWindowHierarchy(out)
        return out.toString(Charsets.UTF_8.name()).take(MAX_DUMP_CHARS)
    }

    private const val POLL_MILLIS = 250L
}

/** Adds the on-screen hierarchy to any failure, so CI logs show what was on screen. */
internal class DumpWindowOnFailure : TestRule {
    override fun apply(
        base: Statement,
        description: Description,
    ): Statement =
        object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (e: Throwable) {
                    throw AssertionError("${e.message}\n--- window hierarchy ---\n${Ui.windowDump()}", e)
                }
            }
        }
}
