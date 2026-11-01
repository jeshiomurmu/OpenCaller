package io.github.jeshiomurmu.opencaller.core.ml.link

import io.github.jeshiomurmu.opencaller.core.ml.TestMl
import io.github.jeshiomurmu.opencaller.core.model.MessageRisk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkCheckerTest {
    private val checker = TestMl.linkChecker("evil-shop.example")

    @Test
    fun lookalikeDomainsAreFlaggedWithTheBrandTheyCopy() =
        runTest {
            // TC-SMS-005
            val digit = checker.check("Your account is locked. Verify at http://paypa1-secure.example/login")
            assertTrue(MessageRisk.LOOKALIKE_LINK in digit.risks)
            assertEquals("PayPal", digit.imitates)
            assertTrue(digit.isDangerous)

            val brandOnOtherDomain = checker.check("Update your KYC at http://sbi-kyc-update.click/4821")
            assertEquals("State Bank of India", brandOnOtherDomain.imitates)
            assertTrue(MessageRisk.SUSPICIOUS_LINK in brandOnOtherDomain.risks)

            val cyrillic = checker.check("Pay now: https://pаypal.com/x") // Cyrillic а
            assertTrue(MessageRisk.MIXED_SCRIPT_LINK in cyrillic.risks)
            assertTrue(MessageRisk.LOOKALIKE_LINK in cyrillic.risks)
        }

    @Test
    fun theBrandsOwnDomainsAndOrdinaryLinksAreClean() =
        runTest {
            assertFalse(checker.check("Pay at https://www.paypal.com/invoice/1 or https://help.chase.com").isDoubtful)
            assertFalse(checker.check("Track it at https://shopnest.example/t/1234").isDoubtful)
            assertFalse(checker.check("No links here, just Rs.120 and Mr.Smith").isDoubtful)
        }

    @Test
    fun shortenersAndListedHostsAreFlagged() =
        runTest {
            assertEquals(setOf(MessageRisk.SHORTENED_LINK), checker.check("Claim at https://bit.ly/3x9k").risks)
            // A sub-domain of a listed host is listed too.
            val listed = checker.check("Sale! https://deals.evil-shop.example/today")
            assertEquals(setOf(MessageRisk.LISTED_LINK), listed.risks)
            assertTrue(listed.isDangerous)
        }

    @Test
    fun hostsAreTakenFromLinksSafely() {
        assertEquals("evil.top", Hosts.of("https://paypal.com@evil.top:8080/x"))
        assertEquals("bank.example", Hosts.of("www.bank.example/login"))
        assertEquals("nagad.com.bd", Hosts.registrable("a.b.nagad.com.bd"))
        assertEquals(listOf("a.b.example", "b.example"), Hosts.chain("a.b.example"))
        assertTrue(Lookalikes.oneEditApart("paypal", "paypai"))
        assertFalse(Lookalikes.oneEditApart("paypal", "paypal"))
        assertTrue(Lookalikes.oneEditApart("amazon", "amazzon"))
    }

    @Test
    fun hostPrefixesMatchTheSharedVectors() {
        // The server computes the same prefixes (fixtures/host_prefix_vectors.csv).
        io.github.jeshiomurmu.opencaller.core.testing.Fixtures.csv("host_prefix_vectors.csv").forEach { (host, prefix) ->
            assertEquals(host, prefix.toLong(16).toInt(), Hosts.prefix(host))
        }
    }
}
