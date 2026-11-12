package io.github.jeshiomurmu.opencaller.feature.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class ForwardingCodesTest {
    @Test
    fun theStandardGsmCodesForwardToTheAssistantNumber() {
        assertEquals(
            listOf("**61*+8809638000001#", "**62*+8809638000001#", "**67*+8809638000001#"),
            ForwardingCodes.forNumber(NUMBER).map { it.code },
        )
        assertEquals(
            listOf(
                ForwardingKind.NO_REPLY,
                ForwardingKind.UNREACHABLE,
                ForwardingKind.BUSY,
            ),
            ForwardingCodes.forNumber(NUMBER).map {
                it.kind
            },
        )
        assertEquals("##002#", ForwardingCodes.CANCEL_ALL)
    }
}
