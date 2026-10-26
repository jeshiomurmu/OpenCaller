package io.github.jeshiomurmu.opencaller.core.ui

import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.CallerVerification
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.model.SpamVerdict
import io.github.jeshiomurmu.opencaller.core.model.VerdictSource
import org.junit.Assert.assertEquals
import org.junit.Test

class WhyReasonsTest {
    private val number = PhoneNumber("+12025550143")

    @Test
    fun liveLookupWarningExplainsTheSourceAndTheReports() {
        val caller = CallerInfo(number, spamVerdict = SpamVerdict(0.9f, RiskLevel.LIKELY_SPAM, "loan", 12, VerdictSource.LIVE_LOOKUP))
        assertEquals(listOf(WhyReason.LiveLookup, WhyReason.Reports(12)), whyReasons(caller, ScreeningReason.NETWORK))
    }

    @Test
    fun cachedAndListedVerdictsNameTheirSource() {
        val cached = CallerInfo(number, spamVerdict = SpamVerdict(0.5f, RiskLevel.SUSPICIOUS, null, 0, VerdictSource.REPUTATION_CACHE))
        val listed = CallerInfo(number, spamVerdict = SpamVerdict(0.95f, RiskLevel.LIKELY_SPAM, null, 3, VerdictSource.SPAM_LIST))
        assertEquals(listOf(WhyReason.EarlierLookup), whyReasons(cached))
        assertEquals(listOf(WhyReason.SpamList, WhyReason.Reports(3)), whyReasons(listed, ScreeningReason.SPAM_LIST))
    }

    @Test
    fun aVerdictWithoutSourceFollowsTheDecision() {
        val blocked = CallerInfo(number, spamVerdict = SpamVerdict(0.9f, RiskLevel.LIKELY_SPAM, null, 0))
        assertEquals(listOf(WhyReason.LiveLookup), whyReasons(blocked, ScreeningReason.NETWORK))
        assertEquals(listOf(WhyReason.SpamList), whyReasons(blocked, ScreeningReason.SPAM_LIST))
    }

    @Test
    fun decisionsByTheUsersOwnChoicesComeFirst() {
        assertEquals(listOf(WhyReason.YourRule), whyReasons(CallerInfo(number), ScreeningReason.RULE))
        assertEquals(listOf(WhyReason.HiddenSetting), whyReasons(null, ScreeningReason.HIDDEN))
        assertEquals(listOf(WhyReason.ForeignSetting), whyReasons(CallerInfo(number), ScreeningReason.FOREIGN))
        assertEquals(listOf(WhyReason.NonContactSetting), whyReasons(CallerInfo(number), ScreeningReason.NON_CONTACT))
    }

    @Test
    fun namesAndCarrierVerificationAreExplained() {
        val business =
            CallerInfo(
                number,
                displayName = "City Pharmacy",
                nameSource = NameSource.VERIFIED_BUSINESS,
                verifiedBusinessId = "verified",
                verification = CallerVerification.PASSED,
            )
        val spoofed = CallerInfo(number, displayName = "Ana", nameSource = NameSource.CONTACT, verification = CallerVerification.FAILED)
        assertEquals(listOf(WhyReason.VerifiedBusiness, WhyReason.CarrierVerified), whyReasons(business))
        assertEquals(listOf(WhyReason.Contact, WhyReason.CarrierFailed), whyReasons(spoofed))
    }

    @Test
    fun nothingKnownSaysSo() {
        assertEquals(listOf(WhyReason.NoData), whyReasons(CallerInfo(number)))
        assertEquals(listOf(WhyReason.NoData), whyReasons(CallerInfo(number, nameSource = NameSource.SELF_REGISTERED)))
    }
}
