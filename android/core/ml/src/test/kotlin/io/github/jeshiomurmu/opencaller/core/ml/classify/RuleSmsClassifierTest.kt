package io.github.jeshiomurmu.opencaller.core.ml.classify

import io.github.jeshiomurmu.opencaller.core.ml.TestMl
import io.github.jeshiomurmu.opencaller.core.ml.otp.OtpExtractor
import io.github.jeshiomurmu.opencaller.core.model.Address
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.SenderId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleSmsClassifierTest {
    private val classifier = RuleSmsClassifier()
    private val links = TestMl.linkChecker()
    private val parser = TestMl.transactionParser()

    private suspend fun categoryOf(
        text: String,
        sender: Address,
    ): MessageCategory =
        classifier
            .classify(ClassifierInput(text, sender, OtpExtractor().extract(text), parser.parse(text, sender), links.check(text)))
            .category

    @Test
    fun eachCategoryHasItsRule() =
        runTest {
            val bank = SenderId("VM-HDFCBK-T")
            val person = PhoneNumber("+15550189")
            assertEquals(MessageCategory.OTP, categoryOf("Your OTP is 482913. Do not share it.", SenderId("JD-SBIOTP-S")))
            assertEquals(
                MessageCategory.TRANSACTION,
                categoryOf("Rs.500.00 spent on HDFC Bank Card x1234 at Metro Fuels on 1-Nov-26.", bank),
            )
            assertEquals(MessageCategory.BILL, categoryOf("Your bill of Rs.499 is due on 5-Nov-26.", SenderId("AD-JIOINF-T")))
            assertEquals(MessageCategory.DELIVERY, categoryOf("Your parcel 12345678 is out for delivery.", SenderId("Steadfast")))
            assertEquals(MessageCategory.PROMOTION, categoryOf("Flat 30% off on mobiles. Shop now!", SenderId("AD-CARTWL-P")))
            assertEquals(MessageCategory.PROMOTION, categoryOf("New arrivals for you.", SenderId("AD-CARTWL-P")))
            assertEquals(MessageCategory.SPAM, categoryOf("Congratulations! You won a lottery. Call now.", PhoneNumber("+919555012345")))
            assertEquals(MessageCategory.PERSONAL, categoryOf("Running 10 mins late, save me a seat?", person))
            assertEquals(MessageCategory.PERSONAL, categoryOf("I sent you $20 for the tickets.", person))
        }

    @Test
    fun phishingNeedsADangerousLinkOrAThreatWithALink() =
        runTest {
            val stranger = PhoneNumber("+15550156")
            assertEquals(
                MessageCategory.PHISHING,
                categoryOf("Your account is locked. Verify at http://paypa1-secure.example", SenderId("ALERTS")),
            )
            assertEquals(
                MessageCategory.PHISHING,
                categoryOf("We couldn't deliver. Pay the redelivery fee at https://swiftparce1.example/fee", stranger),
            )
            // A threat without a link, or a plain link without a threat, is not phishing.
            assertEquals(
                MessageCategory.BILL,
                categoryOf("Your gas bill is due. Please pay to avoid disconnection.", SenderId("Titas Gas")),
            )
            assertEquals(
                MessageCategory.DELIVERY,
                categoryOf("Your order has shipped. Track it at https://shopnest.example/t/1", SenderId("28782")),
            )
        }
}
