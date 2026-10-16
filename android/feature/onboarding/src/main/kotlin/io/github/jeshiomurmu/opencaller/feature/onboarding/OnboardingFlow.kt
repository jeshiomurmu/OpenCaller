package io.github.jeshiomurmu.opencaller.feature.onboarding

import io.github.jeshiomurmu.opencaller.core.model.AccessItem
import io.github.jeshiomurmu.opencaller.core.model.AccessStatus
import io.github.jeshiomurmu.opencaller.core.model.OnboardingStep

/** Order of the onboarding steps and how they map to the (at most five) numbered steps the user sees. */
internal object OnboardingFlow {
    /** Numbered steps shown to the user: Welcome, Privacy, Call screening, Permissions, Extras. */
    const val VISIBLE_STEPS = 5

    private val visibleNumbers =
        mapOf(
            OnboardingStep.WELCOME to 1,
            OnboardingStep.PRIVACY to 2,
            OnboardingStep.CALL_SCREENING to 3,
            OnboardingStep.CONTACTS to 4,
            OnboardingStep.NOTIFICATIONS to 4,
            OnboardingStep.EXTRAS to 5,
            OnboardingStep.DONE to 5,
        )

    /** The numbered step ("Step 4 of 5") shown for [step]. */
    fun visibleNumber(step: OnboardingStep): Int = visibleNumbers.getValue(step)

    /** The role or permission [step] asks for, if any. */
    fun itemOf(step: OnboardingStep): AccessItem? =
        when (step) {
            OnboardingStep.CALL_SCREENING -> AccessItem.CALL_SCREENING
            OnboardingStep.CONTACTS -> AccessItem.CONTACTS
            OnboardingStep.NOTIFICATIONS -> AccessItem.NOTIFICATIONS
            else -> null
        }

    /**
     * The step after [step], skipping requests that are already granted or don't apply on this device
     * (for example notifications before Android 13).
     */
    fun next(
        step: OnboardingStep,
        access: AccessStatus,
    ): OnboardingStep {
        var candidate = step.following()
        while (itemOf(candidate)?.let(access::isSettled) == true) {
            candidate = candidate.following()
        }
        return candidate
    }

    /** Optional items offered on the Extras step, in display order. */
    val EXTRAS: List<AccessItem> = listOf(AccessItem.DEFAULT_PHONE_APP, AccessItem.DEFAULT_SMS_APP, AccessItem.OVERLAY)

    private fun OnboardingStep.following(): OnboardingStep = OnboardingStep.entries.getOrElse(ordinal + 1) { OnboardingStep.DONE }
}
