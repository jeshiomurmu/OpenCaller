package io.github.jeshiomurmu.opencaller.feature.onboarding

import io.github.jeshiomurmu.opencaller.core.model.OnboardingStep
import io.github.jeshiomurmu.opencaller.core.model.PermissionStatus
import io.github.jeshiomurmu.opencaller.core.model.RoleStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFlowTest {
    private val fresh = FakeAccessStatusSource.FRESH_INSTALL

    @Test
    fun walksEveryStepOnAFreshInstall() {
        val visited =
            generateSequence(OnboardingStep.WELCOME) {
                if (it ==
                    OnboardingStep.DONE
                ) {
                    null
                } else {
                    OnboardingFlow.next(it, fresh)
                }
            }.toList()
        assertEquals(OnboardingStep.entries, visited)
    }

    @Test
    fun skipsWhatIsAlreadyGrantedOrNotNeeded() {
        val granted =
            fresh.copy(
                roles = fresh.roles.copy(callScreening = RoleStatus.HELD),
                permissions = fresh.permissions.copy(notifications = PermissionStatus.NOT_REQUIRED),
            )
        assertEquals(OnboardingStep.CONTACTS, OnboardingFlow.next(OnboardingStep.PRIVACY, granted))
        assertEquals(OnboardingStep.EXTRAS, OnboardingFlow.next(OnboardingStep.CONTACTS, granted))
    }

    @Test
    fun neverShowsMoreThanFiveNumberedSteps() {
        val numbers = OnboardingStep.entries.map(OnboardingFlow::visibleNumber)
        assertTrue(numbers.all { it in 1..OnboardingFlow.VISIBLE_STEPS })
        assertEquals(numbers, numbers.sorted())
    }

    @Test
    fun doneStaysDone() {
        assertEquals(OnboardingStep.DONE, OnboardingFlow.next(OnboardingStep.DONE, fresh))
    }
}
