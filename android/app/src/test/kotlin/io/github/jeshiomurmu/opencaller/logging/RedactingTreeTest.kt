package io.github.jeshiomurmu.opencaller.logging

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

class RedactingTreeTest {
    private val logged = mutableListOf<Triple<Int, String, String>>()

    @After
    fun uproot() {
        Timber.uprootAll()
    }

    @Test
    fun redactsNumbersBeforeLogging() {
        Timber.plant(RedactingTree { priority, tag, message -> logged += Triple(priority, tag, message) })

        Timber.tag("Screening").i("Blocked +8801712345678 after 12 ms")

        assertEquals(listOf(Triple(INFO, "Screening", "Blocked +****5678 after 12 ms")), logged)
    }

    @Test
    fun redactsStackTracesAndUsesDefaultTag() {
        Timber.plant(RedactingTree { priority, tag, message -> logged += Triple(priority, tag, message) })

        Timber.w(IllegalStateException("lookup failed for 01712345678"), "lookup")

        val (priority, tag, message) = logged.single()
        assertEquals(WARN, priority)
        assertEquals("OpenCaller", tag)
        assertTrue(message.contains("lookup failed for ****5678"))
        assertFalse(message.contains("01712345678"))
    }

    private companion object {
        // android.util.Log.INFO / WARN.
        const val INFO = 4
        const val WARN = 5
    }
}
