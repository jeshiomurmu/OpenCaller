package io.github.jeshiomurmu.opencaller.core.ml.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SenderHeaderTest {
    @Test
    fun indianHeadersGiveTheHeaderAndTheTrafficType() {
        // TC-OTP-003 (the header; the registry gives the brand)
        assertEquals(SenderHeader("VM", "HDFCBK", HeaderType.TRANSACTIONAL), SenderHeader.parseIndian("VM-HDFCBK-T"))
        assertEquals(HeaderType.SERVICE, SenderHeader.parseIndian("JD-SBIOTP-S")!!.type)
        assertEquals(HeaderType.PROMOTIONAL, SenderHeader.parseIndian("ad-cartwl-p")!!.type)
        assertEquals(HeaderType.GOVERNMENT, SenderHeader.parseIndian("TM-UIDAIG-G")!!.type)
        assertEquals(SenderHeader("AD", "JIOINF", null), SenderHeader.parseIndian("AD-JIOINF"))
    }

    @Test
    fun otherSendersAreNotIndianHeaders() {
        assertNull(SenderHeader.parseIndian("bKash"))
        assertNull(SenderHeader.parseIndian("+919555012345"))
        assertNull(SenderHeader.parseIndian("VM-HDFCBK-X"))
        assertNull(SenderHeader.parseIndian("BRAC BANK"))
    }
}
