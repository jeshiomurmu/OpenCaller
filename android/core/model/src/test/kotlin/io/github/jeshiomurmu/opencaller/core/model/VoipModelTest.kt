package io.github.jeshiomurmu.opencaller.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Server names of Flash kinds, call states and video jobs, and what an unknown one means. */
class VoipModelTest {
    @Test
    fun flashKindsMatchTheServer() {
        val server = setOf("call_me", "on_my_way", "running_late", "cant_talk", "arrived", "call_you_back")
        assertEquals(server, FlashKind.entries.map { it.wireName }.toSet())
        assertEquals(FlashKind.CANT_TALK, FlashKind.parse("cant_talk"))
        assertNull(FlashKind.parse("see you at 5"))
        assertNull(FlashKind.parse(null))
    }

    @Test
    fun callStates() {
        assertEquals(AppCallState.RINGING, AppCallState.parse("ringing"))
        assertEquals(AppCallState.ENDED, AppCallState.parse("something new"))
        assertFalse(AppCallState.ACCEPTED.isOver)
        assertTrue(AppCallState.MISSED.isOver)
    }

    @Test
    fun videoJobs() {
        assertEquals(VideoJob.PROCESSING, VideoJob.parse("processing"))
        assertEquals(VideoJob.NONE, VideoJob.parse("later"))
    }
}
