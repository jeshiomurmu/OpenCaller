package io.github.jeshiomurmu.opencaller.core.ui

import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.LineType
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.SpamVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CallerCardStateTest {
    private val number = PhoneNumber("+12015550123")
    private val spam = SpamVerdict(0.9f, RiskLevel.LIKELY_SPAM, "Loan", 3)
    private val suspicious = SpamVerdict(0.7f, RiskLevel.SUSPICIOUS, null, 1)

    @Test
    fun everyStateIsReachable() {
        assertEquals(CallerCardState.HIDDEN, CallerCardState.of(CallerInfo(number = null)))
        assertEquals(CallerCardState.CONTACT, CallerCardState.of(CallerInfo(number, "Amina", NameSource.CONTACT)))
        assertEquals(CallerCardState.SPAM, CallerCardState.of(CallerInfo(number, spamVerdict = spam)))
        assertEquals(CallerCardState.VERIFIED_BUSINESS, CallerCardState.of(CallerInfo(number, verifiedBusinessId = "b1")))
        assertEquals(CallerCardState.SUSPICIOUS, CallerCardState.of(CallerInfo(number, spamVerdict = suspicious)))
        assertEquals(CallerCardState.SELF_REGISTERED, CallerCardState.of(CallerInfo(number, "Jo", NameSource.SELF_REGISTERED)))
        assertEquals(CallerCardState.UNKNOWN, CallerCardState.of(CallerInfo(number)))
        assertEquals(
            CallerCardState.entries.size,
            CallerCardSamples()
                .values
                .map { CallerCardState.of(it) }
                .toSet()
                .size,
        )
    }

    @Test
    fun contactsNeverShowAsSpamAndSpamBeatsABusinessName() {
        assertEquals(CallerCardState.CONTACT, CallerCardState.of(CallerInfo(number, "Amina", NameSource.CONTACT, spamVerdict = spam)))
        assertEquals(
            CallerCardState.SPAM,
            CallerCardState.of(CallerInfo(number, "Bank", NameSource.VERIFIED_BUSINESS, spamVerdict = spam)),
        )
        assertEquals(CallerCardState.UNKNOWN, CallerCardState.of(CallerInfo(number, spamVerdict = spam.copy(level = RiskLevel.NONE))))
    }

    @Test
    fun lineTypesHaveLabelsExceptUnknown() {
        LineType.entries.filter { it != LineType.UNKNOWN }.forEach { assert(it.labelRes() != null) { it } }
        assertNull(LineType.UNKNOWN.labelRes())
    }
}
