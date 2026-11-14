package io.github.jeshiomurmu.opencaller.core.telephony.incall

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RecordingLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinePlanTest {
    @Test
    fun theDialStringPausesThenSendsTheCode() {
        assertEquals("+8809638000000,,12345678#", LinePlan.dialString(LINE))
    }

    @Test
    fun theLineIsRecognisedInEveryFormTelecomShows() {
        assertTrue(LinePlan.isLine("+8809638000000", LINE.number))
        assertTrue(LinePlan.isLine("8809638000000", LINE.number))
        assertTrue(LinePlan.isLine("9638000000", LINE.number))
        assertTrue(LinePlan.isLine("+8809638000000,,12345678#", LINE.number))
        assertFalse(LinePlan.isLine("+8801711000001", LINE.number))
        assertFalse(LinePlan.isLine("000", LINE.number))
        assertFalse(LinePlan.isLine(null, LINE.number))
    }

    @Test
    fun serverErrorsMapToReasons() {
        assertEquals(RecordingFailure.SUPPORTER_REQUIRED, LinePlan.failureOf(AppError.Validation("supporter_required")))
        assertEquals(RecordingFailure.UNAVAILABLE, LinePlan.failureOf(AppError.Validation("recording_unavailable")))
        assertEquals(RecordingFailure.NOT_SIGNED_IN, LinePlan.failureOf(AppError.Auth))
        assertEquals(RecordingFailure.NOT_SIGNED_IN, LinePlan.failureOf(AppError.ServerNotConfigured))
        assertEquals(RecordingFailure.NETWORK, LinePlan.failureOf(AppError.Network))
        // The voice scan's refusal reads like recording's: not offered here.
        assertEquals(RecordingFailure.UNAVAILABLE, LinePlan.failureOf(AppError.Validation("scanner_unavailable")))
    }

    private companion object {
        val LINE = RecordingLine("0f8fad5b-d9cb-469f-a165-70867728950e", PhoneNumber("+8809638000000"), "12345678", 0)
    }
}
