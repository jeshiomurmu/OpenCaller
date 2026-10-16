package io.github.jeshiomurmu.opencaller.core.common

/**
 * Source of the current time, injected so logic never calls `System.currentTimeMillis()` directly
 * (docs/rules.md §4) and tests can control time.
 */
fun interface Clock {
    /** Current wall-clock time in milliseconds since the Unix epoch. */
    fun nowMillis(): Long
}

/** Production [Clock] backed by the system wall clock. */
object SystemWallClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
