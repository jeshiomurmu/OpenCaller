package io.github.jeshiomurmu.opencaller.onboarding

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jeshiomurmu.opencaller.MainActivity
import io.github.jeshiomurmu.opencaller.onboarding.Ui.isShown
import io.github.jeshiomurmu.opencaller.onboarding.Ui.string
import io.github.jeshiomurmu.opencaller.onboarding.Ui.tap
import io.github.jeshiomurmu.opencaller.onboarding.Ui.waitFor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import io.github.jeshiomurmu.opencaller.R as AppR
import io.github.jeshiomurmu.opencaller.core.ui.R as UiR
import io.github.jeshiomurmu.opencaller.feature.onboarding.R as OnboardingR
import io.github.jeshiomurmu.opencaller.feature.settings.R as SettingsR

/**
 * Onboarding test cases TC-ONB-001…008 (docs/testing.md), run on API 29 and API 36 emulators in CI. Each test
 * starts from cleared app data (test orchestrator `clearPackageData`) and without the call screening role.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingTest {
    @get:Rule
    val dumpOnFailure = DumpWindowOnFailure(::cleanUp)

    private var scenario: ActivityScenario<MainActivity>? = null

    private val notNow get() = string(UiR.string.explainer_not_now)
    private val callScreeningTitle get() = string(UiR.string.explain_call_screening_title)
    private val contactsTitle get() = string(UiR.string.explain_contacts_title)
    private val banner get() = string(AppR.string.banner_screening_off)
    private val callsTab get() = string(AppR.string.tab_calls)

    @Before
    fun setUp() {
        Ui.setCallScreeningRole(held = false)
        Ui.device.setOrientationNatural()
    }

    private fun cleanUp() {
        scenario?.close()
        Ui.device.setOrientationNatural()
        Ui.setCallScreeningRole(held = false)
    }

    @Test
    fun tcOnb001_grantingCallScreeningMovesToTheNextStep() {
        launchAndPassIntro()
        waitFor(callScreeningTitle)
        tap(string(UiR.string.explainer_continue))
        Ui.acceptRoleDialog()

        waitFor(contactsTitle)
        assertTrue("OpenCaller holds ROLE_CALL_SCREENING", Ui.holdsCallScreeningRole())
    }

    @Test
    fun tcOnb002_denyingCallScreeningShowsTheBannerAndAFixInSettings() {
        launchAndPassIntro()
        waitFor(callScreeningTitle)
        tap(string(UiR.string.explainer_continue))
        Ui.tapSystemButton(Ui.roleDecline)

        waitFor(contactsTitle)
        assertFalse(Ui.holdsCallScreeningRole())
        skipRemainingSteps()

        waitFor(banner)
        tap(string(AppR.string.banner_fix))
        // Fix shows the explainer first; back on the list, the row still offers Fix.
        waitFor(callScreeningTitle)
        tap(notNow)
        waitFor(string(SettingsR.string.permissions_title))
        assertTrue(isShown(string(SettingsR.string.permissions_fix)))
    }

    @Test
    fun tcOnb003_skippingTheDialerRoleKeepsScreeningOnlyMode() {
        Ui.setCallScreeningRole(held = true)
        launchAndPassIntro()
        // Call screening is already on, so onboarding goes straight to contacts.
        waitFor(contactsTitle)
        skipRemainingSteps()

        waitFor(callsTab)
        waitFor(string(AppR.string.calls_default_app_title))
        assertFalse("no degraded banner in screening-only mode", isShown(banner, timeoutMillis = SHORT_WAIT_MILLIS))
    }

    @Test
    fun tcOnb005_revokingTheRoleLaterShowsTheBannerOnResume() {
        Ui.setCallScreeningRole(held = true)
        launchAndPassIntro()
        skipRemainingSteps()
        waitFor(callsTab)
        assertFalse(isShown(banner, timeoutMillis = SHORT_WAIT_MILLIS))

        Ui.setCallScreeningRole(held = false)
        Ui.device.pressHome()
        Ui.relaunchFromLauncher()

        waitFor(banner)
    }

    @Test
    fun tcOnb006_denyingNotificationsFallsBackToInAppHistory() {
        assumeTrue("POST_NOTIFICATIONS exists from Android 13", Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        launchAndPassIntro()
        waitFor(callScreeningTitle)
        tap(notNow)
        waitFor(contactsTitle)
        tap(notNow)
        waitFor(string(UiR.string.explain_notifications_title))
        tap(string(UiR.string.explainer_continue))
        Ui.tapSystemButton(Ui.permissionDeny)

        waitFor(string(OnboardingR.string.onboarding_extras_title))
        tap(string(OnboardingR.string.onboarding_finish))
        openPermissionsStatus()
        Ui.scrollTo(string(SettingsR.string.permissions_after_call_history))
    }

    @Test
    fun tcOnb007_denyingTheOverlayFallsBackToANotification() {
        launchAndPassIntro()
        skipTo(string(OnboardingR.string.onboarding_extras_title))
        tap(string(OnboardingR.string.onboarding_set_up))
        waitFor(string(UiR.string.explain_overlay_title))
        tap(string(UiR.string.explainer_open_settings))
        // System overlay settings: leave without allowing.
        Ui.device.waitForIdle()
        Ui.device.pressBack()

        waitFor(string(OnboardingR.string.onboarding_extras_title))
        tap(string(OnboardingR.string.onboarding_finish))
        openPermissionsStatus()
        Ui.scrollTo(string(SettingsR.string.permissions_caller_id_notification))
    }

    @Test
    fun tcOnb008_rotationAndRecreationResumeAtTheSameStep() {
        launchAndPassIntro()
        val stepThree = string(OnboardingR.string.onboarding_progress, CALL_SCREENING_STEP, VISIBLE_STEPS)
        waitFor(callScreeningTitle)
        waitFor(stepThree)

        Ui.device.setOrientationLeft()
        waitFor(callScreeningTitle)
        waitFor(stepThree)
        Ui.device.setOrientationNatural()

        // A new activity and view model read the step back from user settings (the emulator smoke script
        // also kills the process to check real process death).
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitFor(callScreeningTitle)
        waitFor(stepThree)
    }

    private fun launchAndPassIntro() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        tap(string(OnboardingR.string.onboarding_get_started))
        waitFor(string(OnboardingR.string.onboarding_privacy_title))
        tap(string(OnboardingR.string.onboarding_continue))
    }

    /** Answers "Not now" until [text] is on screen. */
    private fun skipTo(text: String) {
        repeat(MAX_STEPS) {
            if (isShown(text, timeoutMillis = SHORT_WAIT_MILLIS)) return
            tap(notNow)
        }
        waitFor(text)
    }

    private fun skipRemainingSteps() {
        skipTo(string(OnboardingR.string.onboarding_finish))
        tap(string(OnboardingR.string.onboarding_finish))
    }

    private fun openPermissionsStatus() {
        tap(string(AppR.string.tab_me))
        tap(string(SettingsR.string.settings_permissions))
        waitFor(string(SettingsR.string.permissions_title))
    }

    private companion object {
        const val SHORT_WAIT_MILLIS = 2_000L
        const val MAX_STEPS = 5
        const val CALL_SCREENING_STEP = 3
        const val VISIBLE_STEPS = 5
    }
}
