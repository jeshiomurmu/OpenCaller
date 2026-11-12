package io.github.jeshiomurmu.opencaller.core.data

import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantAction
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantEvent
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantMessages
import io.github.jeshiomurmu.opencaller.core.data.assistant.toEntity
import io.github.jeshiomurmu.opencaller.core.data.assistant.toModel
import io.github.jeshiomurmu.opencaller.core.model.AssistantUsage
import io.github.jeshiomurmu.opencaller.core.model.CallUrgency
import io.github.jeshiomurmu.opencaller.core.model.CallerDetails
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreenedCall
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEnd
import io.github.jeshiomurmu.opencaller.core.model.Speaker
import io.github.jeshiomurmu.opencaller.core.model.TranscriptLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The assistant's data-channel messages (the agent's `assistant_protocol.py`) and the history rows (ADR 0010). */
class AssistantDataTest {
    private fun parse(json: String) = AssistantMessages.parse(json.encodeToByteArray())

    @Test
    fun theAgentsMessagesAreRead() {
        assertEquals(
            AssistantEvent.Hello(PhoneNumber("+8801711000099")),
            parse("""{"t":"hello","v":1,"caller":"+8801711000099","callerHidden":false}"""),
        )
        assertEquals(AssistantEvent.Hello(null), parse("""{"t":"hello","v":1,"caller":null,"callerHidden":true}"""))
        assertEquals(AssistantEvent.Hello(null), parse("""{"t":"hello","caller":"anonymous"}"""))
        assertEquals(AssistantEvent.Partial("It's Ra"), parse("""{"t":"partial","text":"It's Ra"}"""))
        assertEquals(
            AssistantEvent.Line(TranscriptLine(Speaker.CALLER, "It's Rahim.")),
            parse("""{"t":"line","role":"caller","text":"It's Rahim."}"""),
        )
        assertEquals(
            AssistantEvent.Details(CallerDetails("Rahim", null, CallUrgency.HIGH, likelySpam = true)),
            parse("""{"t":"details","name":"Rahim","reason":null,"urgency":"high","likelySpam":true}"""),
        )
        assertEquals(AssistantEvent.Ended(ScreeningEnd.TIME_UP), parse("""{"t":"ended","reason":"time"}"""))
        assertEquals(AssistantEvent.Ended(ScreeningEnd.UNKNOWN), parse("""{"t":"ended","reason":"new-reason"}"""))
        for (junk in listOf("""{"t":"line","role":"robot","text":"x"}""", """{"t":"partial"}""", "[1]", "nope", """{"t":"future"}""")) {
            assertNull(junk, parse(junk))
        }
    }

    @Test
    fun theAppsActionsAreWritten() {
        assertEquals(
            """{"t":"reply","text":"Five minutes"}""",
            AssistantMessages.encode(AssistantAction.Reply("  Five minutes ")).decodeToString(),
        )
        assertEquals(
            200,
            Regex(
                "\"text\":\"(x*)\"",
            ).find(AssistantMessages.encode(AssistantAction.Reply("x".repeat(500))).decodeToString())!!.groupValues[1].length,
        )
        assertEquals("""{"t":"callback"}""", AssistantMessages.encode(AssistantAction.CallBack).decodeToString())
        assertEquals("""{"t":"spam"}""", AssistantMessages.encode(AssistantAction.Spam).decodeToString())
    }

    @Test
    fun aScreenedCallSurvivesItsRow() {
        val call =
            ScreenedCall(
                id = "c1",
                startedAtMillis = 5,
                caller = PhoneNumber("+8801711000099"),
                details = CallerDetails("Rahim", "Appointment", CallUrgency.LOW, likelySpam = false),
                transcript = listOf(TranscriptLine(Speaker.ASSISTANT, "Hello."), TranscriptLine(Speaker.CALLER, "Two\nlines")),
                end = ScreeningEnd.CALL_BACK,
            )
        val row = call.toEntity()
        assertEquals("A Hello.\nC Two lines", row.transcript)
        assertEquals(
            call.copy(transcript = listOf(TranscriptLine(Speaker.ASSISTANT, "Hello."), TranscriptLine(Speaker.CALLER, "Two lines"))),
            row.toModel(),
        )
        assertNull(
            call
                .copy(caller = null)
                .toEntity()
                .toModel()
                .caller,
        )
    }

    @Test
    fun theMeterRoundsUpAndKnowsTheCap() {
        assertEquals(2, AssistantUsage("2026-11", 61, 100, 5).usedMinutes)
        assertTrue(AssistantUsage("2026-11", 6_000, 100, 5).exhausted)
        assertFalse(AssistantUsage("2026-11", 6_000, 0, 5).exhausted)
    }
}
