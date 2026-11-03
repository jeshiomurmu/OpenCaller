package io.github.jeshiomurmu.opencaller.core.telephony.messaging

import io.github.jeshiomurmu.opencaller.core.data.RegisteredSender
import io.github.jeshiomurmu.opencaller.core.ml.otp.OtpExtraction
import io.github.jeshiomurmu.opencaller.core.ml.sender.HeaderType
import io.github.jeshiomurmu.opencaller.core.ml.transaction.Direction
import io.github.jeshiomurmu.opencaller.core.ml.transaction.TransactionInfo
import io.github.jeshiomurmu.opencaller.core.model.BusinessCategory
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.SenderId
import io.github.jeshiomurmu.opencaller.core.model.VerifiedBusiness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SenderResolverTest {
    private val registry = FakeRegistry()
    private var contact: String? = null
    private val resolver = senderResolver({ contact }, registry, region = "IN")

    private suspend fun resolve(
        raw: String,
        text: String = "Hello",
        hints: TextHints = TextHints(),
    ) = resolver.resolve(raw, resolver.addressOf(raw), text, hints)

    @Test
    fun tcOtp003AnIndianHeaderGivesTheRegisteredBrandAndItsTrafficType() =
        runTest {
            registry.senders["IN" to "HDFCBK"] = RegisteredSender("HDFC Bank", "bank", "dlt", null)
            val sender = resolve("VM-HDFCBK-T")
            assertEquals("HDFC Bank", sender.name)
            assertEquals(NameSource.SENDER_REGISTRY, sender.source)
            assertEquals(HeaderType.TRANSACTIONAL, sender.headerType)
            assertEquals("bank", sender.registryCategory)
            assertEquals(SenderId("VM-HDFCBK-T"), sender.address)
        }

    @Test
    fun tcOtp004AnAppHashGivesTheApp() =
        runTest {
            registry.apps["FA+9qCX9VSu"] = "Example Pay"
            val sender = resolve("57575", hints = TextHints(otp = OtpExtraction("123456", appHash = "FA+9qCX9VSu")))
            assertEquals("Example Pay", sender.name)
            assertEquals(NameSource.SENDER_REGISTRY, sender.source)
        }

    @Test
    fun theContactComesFirstAndNumbersAreReadInTheHomeFormat() =
        runTest {
            contact = "Ana"
            registry.senders["IN" to "HDFCBK"] = RegisteredSender("HDFC Bank", "bank", "dlt", null)
            val sender = resolve("VM-HDFCBK-T")
            assertEquals("Ana", sender.name)
            assertTrue(sender.isContact)
            contact = null
            assertEquals(PhoneNumber("+919876543210"), resolve("09876543210").address)
        }

    @Test
    fun aVerifiedBusinessNumberNamesItselfAfterTheContacts() =
        runTest {
            val number = PhoneNumber("+919555012345")
            registry.businesses[number] =
                VerifiedBusiness(number, "0b6a3c52-1f0e-4f7b-9d2a-5c1e8f3a7b10", "Example Bank", BusinessCategory.BANK, null, null)
            val sender = resolve("+919555012345", text = "Scam Bank: hello")
            assertEquals("Example Bank", sender.name)
            assertEquals(NameSource.VERIFIED_BUSINESS, sender.source)
            assertEquals("0b6a3c52-1f0e-4f7b-9d2a-5c1e8f3a7b10", sender.verifiedBusinessId)
            assertEquals("bank", sender.registryCategory)
            contact = "Ana"
            assertEquals(NameSource.CONTACT, resolve("+919555012345").source)
        }

    @Test
    fun templatesNameBusinessesButNeverPhoneNumbers() =
        runTest {
            val bank = TextHints(transaction = TransactionInfo(BigDecimal.TEN, "INR", Direction.DEBIT, bank = "HDFC Bank"))
            assertEquals(NameSource.DEVICE_INFERENCE, resolve("AD-NEWBNK-T", hints = bank).source)
            assertEquals("Northpeak Bank", resolve("NPBANK", text = "Northpeak Bank: your card was used").name)
            // A scammer's "Bank:" prefix from a phone number is not a name.
            val scam = resolve("+919555012345", text = "Northpeak Bank: your account is locked", hints = bank)
            assertNull(scam.name)
            assertNull(scam.source)
            // The site of an origin-bound code names the sender of a number too.
            assertEquals("example.com", resolve("+919555012345", hints = TextHints(OtpExtraction("1234", origin = "example.com"))).name)
        }
}
