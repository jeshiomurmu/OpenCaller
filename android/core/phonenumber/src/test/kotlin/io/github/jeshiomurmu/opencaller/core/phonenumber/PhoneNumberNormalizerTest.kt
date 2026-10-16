package io.github.jeshiomurmu.opencaller.core.phonenumber

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.errorOrNull
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.model.LineType
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.SenderId
import io.github.jeshiomurmu.opencaller.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PhoneNumberNormalizerTest {
    private val normalizer = PhoneNumberNormalizer()

    // TC-PN-001: national BD number; TC-PN-002: IN number with spaces/dashes; plus US, GB, NG, AE fixtures.
    @Test
    fun normalizesFixtureNumbers() {
        val rows = Fixtures.csv("numbers.csv")
        assertEquals(setOf("BD", "IN", "US", "GB", "NG", "AE"), rows.map { it[5] }.toSet())
        rows.map(::NumberFixture).forEach { fixture ->
            val number = normalizer.normalize(fixture.raw, fixture.defaultRegion).getOrNull()
            assertEquals(fixture.id, PhoneNumber(fixture.e164), number)
            checkNotNull(number)
            assertEquals(fixture.id, LineType.valueOf(fixture.type), normalizer.numberType(number))
            assertEquals(fixture.id, fixture.region, normalizer.regionCode(number))
        }
    }

    @Test
    fun tcPn001NationalBangladeshNumber() {
        assertEquals(AppResult.Success(PhoneNumber("+8801712345678")), normalizer.normalize("01712345678", "BD"))
    }

    @Test
    fun tcPn002IndianNumberWithSpacesAndDashes() {
        assertEquals(AppResult.Success(PhoneNumber("+918123456789")), normalizer.normalize(" +91 81234-56789 ", "in"))
    }

    // TC-PN-003: short codes and alphanumeric senders are SenderIds, not E.164.
    @Test
    fun tcPn003SendersAreNotPhoneNumbers() {
        listOf("AD-HDFCBK" to "IN", "Google" to "US", "VM-AIRTEL" to "IN", "12345" to "IN", "57575" to "US").forEach { (raw, region) ->
            assertEquals(raw, AppResult.Success(SenderId(raw)), normalizer.parseAddress(raw, region))
            assertTrue(raw, normalizer.normalize(raw, region) is AppResult.Failure)
        }
    }

    @Test
    fun parseAddressReturnsPhoneNumbersForDialableInput() {
        assertEquals(AppResult.Success(PhoneNumber("+447400123456")), normalizer.parseAddress("07400 123456", "GB"))
    }

    @Test
    fun parseAddressRejectsInvalidSenders() {
        listOf("", "A".repeat(SenderId.MAX_LENGTH + 1), "123456789012", "Bad\u0007Sender").forEach {
            assertTrue(it, normalizer.parseAddress(it, "IN").errorOrNull() is AppError.Validation)
        }
    }

    // TC-PN-004: invalid input is a Validation error, never a crash.
    @Test
    fun tcPn004InvalidInputIsValidationError() {
        val invalid = listOf("", "   ", "abc", "+", "0171234", "+999123456789", "1".repeat(80), "++88017", "01712345678")
        invalid.forEachIndexed { index, raw ->
            val region = if (index == invalid.lastIndex) "XX" else "BD"
            assertTrue(raw, normalizer.normalize(raw, region).errorOrNull() is AppError.Validation)
        }
    }

    @Test
    fun randomInputNeverThrows() {
        val random = Random(seed = 42)
        val alphabet = "0123456789+-() #*abcXYZ."
        repeat(2_000) {
            val raw = (0 until random.nextInt(0, 24)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            normalizer.normalize(raw, "US")
            normalizer.parseAddress(raw, "IN")
        }
    }

    @Test
    fun countryCallingCodeAndForeignDetection() {
        val bd = PhoneNumber("+8801712345678")
        val us = PhoneNumber("+12015550123")
        assertEquals(880, normalizer.countryCallingCode(bd))
        assertFalse(normalizer.isForeign(bd, "BD"))
        assertTrue(normalizer.isForeign(bd, "in"))
        // US and CA share +1, so a US number is not foreign for a Canadian SIM.
        assertFalse(normalizer.isForeign(us, "CA"))
        // Unknown SIM region fails open.
        assertFalse(normalizer.isForeign(bd, "ZZ"))
    }

    @Test
    fun nonGeographicNumberHasNoRegion() {
        // +800 is the International Freephone Service (non-geographic).
        val number = normalizer.normalize("+800 1234 5678", "US").getOrNull()
        checkNotNull(number)
        assertNull(normalizer.regionCode(number))
        assertEquals(LineType.TOLL_FREE, normalizer.numberType(number))
    }

    @Test
    fun fromHandleReadsTelephonyHandles() {
        assertEquals(PhoneNumber("+12015550123"), normalizer.fromHandle("+1 (201) 555-0123", "BD"))
        assertEquals(PhoneNumber("+8801712345678"), normalizer.fromHandle("01712345678", "BD"))
        // +1 555 is not an assigned area code, but the rules must still see the number (TC-BLK-003).
        assertEquals(PhoneNumber("+15551230001"), normalizer.fromHandle("+15551230001", "US"))
        assertNull(normalizer.fromHandle("", "US"))
        assertNull(normalizer.fromHandle("  ", "US"))
        assertNull(normalizer.fromHandle("123", "US"))
        assertNull(normalizer.fromHandle("anonymous", "US"))
    }

    @Test
    fun countryOfFallsBackToTheCallingCodesMainRegion() {
        assertEquals("US", normalizer.countryOf(PhoneNumber("+12015550123")))
        assertEquals("US", normalizer.countryOf(PhoneNumber("+15551230001")))
        assertEquals("BD", normalizer.countryOf(PhoneNumber("+8801712345678")))
        val freephone = checkNotNull(normalizer.normalize("+800 1234 5678", "US").getOrNull())
        assertNull(normalizer.countryOf(freephone))
    }

    @Test
    fun displayFormatsAreNationalAtHome() {
        val us = PhoneNumber("+12015550123")
        assertEquals("(201) 555-0123", normalizer.format(us, "us"))
        assertEquals("+1 201-555-0123", normalizer.format(us, "BD"))
        assertEquals("+1 201-555-0123", normalizer.format(us, null))
        assertEquals("01712-345678", normalizer.format(PhoneNumber("+8801712345678"), "BD"))
        assertEquals("+880 ••• 5678", normalizer.masked(PhoneNumber("+8801712345678")))
    }

    @Test
    fun exampleNumbersAreValidMobileNumbersOfTheRegion() {
        listOf("BD", "us", "IN", "GB", "NG", "AE").forEach { region ->
            val example = checkNotNull(normalizer.exampleNumber(region)) { region }
            assertEquals(region.uppercase(), normalizer.regionCode(example))
        }
        assertNull(normalizer.exampleNumber("ZZ"))
    }

    @Test
    fun supportedRegions() {
        assertTrue(normalizer.isSupportedRegion("bd"))
        assertTrue(normalizer.isSupportedRegion("US"))
        assertFalse(normalizer.isSupportedRegion("ZZ"))
        assertFalse(normalizer.isSupportedRegion("USA"))
    }

    /** One row of `fixtures/numbers.csv`. */
    private class NumberFixture(
        row: List<String>,
    ) {
        val id = row[0]
        val raw = row[1]
        val defaultRegion = row[2]
        val e164 = row[3]
        val type = row[4]
        val region = row[5]
    }
}
