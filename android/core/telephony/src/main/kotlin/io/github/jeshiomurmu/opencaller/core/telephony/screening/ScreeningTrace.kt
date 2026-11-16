package io.github.jeshiomurmu.opencaller.core.telephony.screening

import android.os.Trace
import java.util.concurrent.atomic.AtomicInteger

/**
 * Marks each screening decision as a section in system traces, so Macrobenchmark's `TraceSectionMetric` (the
 * `:benchmark` module) and Perfetto can time it. A decision may resume on another thread, so sections are
 * asynchronous and matched by a cookie. [NONE] does nothing (JVM tests, the micro-benchmark).
 */
interface ScreeningTrace {
    /** Starts a section and returns its cookie. */
    fun begin(): Int

    /** Ends the section [cookie] started. */
    fun end(cookie: Int)

    /** No tracing. */
    companion object {
        /** The trace section's name, as `:benchmark` reads it. */
        const val SECTION = "OpenCaller.screen"

        /** Does nothing. */
        val NONE: ScreeningTrace =
            object : ScreeningTrace {
                override fun begin(): Int = 0

                override fun end(cookie: Int) = Unit
            }
    }
}

/** System trace sections (`android.os.Trace` async sections, API 29+); cheap when no trace is being recorded. */
class AndroidScreeningTrace : ScreeningTrace {
    private val cookies = AtomicInteger()

    override fun begin(): Int = cookies.incrementAndGet().also { Trace.beginAsyncSection(ScreeningTrace.SECTION, it) }

    override fun end(cookie: Int) = Trace.endAsyncSection(ScreeningTrace.SECTION, cookie)
}
