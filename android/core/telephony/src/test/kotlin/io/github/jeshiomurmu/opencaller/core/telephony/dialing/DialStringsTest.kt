package io.github.jeshiomurmu.opencaller.core.telephony.dialing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TC-DIAL-004 (the dial pad side): carrier codes pass through unchanged; secret codes go to Android. */
class DialStringsTest {
    @Test
    fun numbersAreDialled() {
        assertEquals(DialAction.Number("+12025550143"), DialStrings.classify("+12025550143"))
        assertEquals(DialAction.Number("2025550143,1234"), DialStrings.classify(" 2025550143,1234 "))
        assertEquals(DialAction.Number("112"), DialStrings.classify("112"))
    }

    @Test
    fun tcDial004CarrierCodesPassThroughUnchanged() {
        listOf("*123#", "*#21#", "##002#", "*100*1#", "#31#2025550143", "*#06#").forEach { code ->
            assertEquals(DialAction.CarrierCode(code), DialStrings.classify(code))
        }
    }

    @Test
    fun secretCodesAreHandedToAndroid() {
        assertEquals(DialAction.SecretCode("4636"), DialStrings.classify("*#*#4636#*#*"))
        // Not the secret code shape: a carrier code.
        assertEquals(DialAction.CarrierCode("*#*#4636#*"), DialStrings.classify("*#*#4636#*"))
        assertEquals(DialAction.CarrierCode("*#*##*#*"), DialStrings.classify("*#*##*#*"))
    }

    @Test
    fun nothingToDial() {
        assertNull(DialStrings.classify(""))
        assertNull(DialStrings.classify("   "))
    }
}
