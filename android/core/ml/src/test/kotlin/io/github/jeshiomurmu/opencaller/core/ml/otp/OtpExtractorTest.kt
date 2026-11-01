package io.github.jeshiomurmu.opencaller.core.ml.otp

import io.github.jeshiomurmu.opencaller.core.model.SenderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OtpExtractorTest {
    private val extractor = OtpExtractor()

    @Test
    fun originBoundCodeGivesTheOriginAndTheCode() {
        // TC-OTP-002
        val found = extractor.extract("Your code is 123456.\n\n@example.com #123456")!!
        assertEquals("123456", found.code)
        assertEquals("example.com", found.origin)

        val alone = extractor.extract("@example.com #123456")!!
        assertEquals("example.com", alone.origin)
        assertEquals("123456", alone.code)
    }

    @Test
    fun originBoundLineWithAnEmbeddedOriginKeepsTheTopLevelOne() {
        val line = OriginBoundCode.parse("Your passcode: 4455\n\n@Tallyfox.example #4455 @pay.tallyfox.example")!!
        assertEquals("tallyfox.example", line.origin)
        assertEquals("pay.tallyfox.example", line.embeddedOrigin)
        assertNull(OriginBoundCode.parse("Email me @ home #1234"))
    }

    @Test
    fun appHashIsReadAndNotMistakenForTheCode() {
        // TC-OTP-004 (the hash; SenderResolver maps it to an app)
        val found = extractor.extract("<#> Your Brightpay code is 482913\nFA+9qCX9VSu")!!
        assertEquals("482913", found.code)
        assertEquals("FA+9qCX9VSu", found.appHash)
        assertNull(AppHash.find("Thanks for the information"))
        assertNull(AppHash.find("Call me\nInformation"))
    }

    @Test
    fun codesInBengaliAndDevanagariDigitsAreReadInAsciiWithTheirExpiry() {
        val bengali = extractor.extract("আপনার ওটিপি কোড ৪৮২৯১৩। এটি ৫ মিনিটের জন্য বৈধ।")!!
        assertEquals("482913", bengali.code)
        assertEquals(5, bengali.validForMinutes)
        val hindi = extractor.extract("आपका ओटीपी ७३१९ है। यह १० मिनट के लिए मान्य है।")!!
        assertEquals("7319", hindi.code)
        assertEquals(10, hindi.validForMinutes)
    }

    @Test
    fun amountsCardDigitsAndPhoneNumbersAreNotCodes() {
        val found =
            extractor.extract(
                "4827 is the OTP for Rs.1250 at Metro Fuels on card ending 9921. Valid for 10 mins. Call 1800-555-0100",
            )!!
        assertEquals("4827", found.code)
        assertEquals(10, found.validForMinutes)
        assertEquals("88213945", extractor.extract("Your one-time passcode is 88213945. Card XX4412 was used.")!!.code)
        assertEquals("482913", extractor.extract("G-482913 is your verification code.")!!.code)
    }

    @Test
    fun messagesWithoutACodeGiveNothing() {
        assertNull(extractor.extract("Flat no 4021, building C. Come by 8."))
        assertNull(extractor.extract("The code for the gate changed, ask me when you're here."))
        assertNull(extractor.extract("Use code SAVE20 for 20% off. Call +1 555-0123"))
        assertNull(extractor.extract("Verify your PIN at https://bkash-verify.xyz/4821 now"))
    }

    @Test
    fun theStoredInfoExpiresAfterTheStatedTimeAndNeverPrintsTheCode() {
        val found = extractor.extract("Your OTP is 551234. It expires in 3 minutes.")!!
        val info = found.toOtpInfo(SenderId("NAGAD"), receivedAtMillis = 1_000L)
        assertEquals(1_000L + 3 * 60_000L, info.expiresAtMillis)
        assertFalse(found.toString().contains("551234"))
        assertEquals(60, ValidFor.minutes("valid for 1 hour"))
        assertNull(ValidFor.minutes("no time here"))
    }
}
