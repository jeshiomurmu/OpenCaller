package io.github.jeshiomurmu.opencaller.core.testing

import io.github.jeshiomurmu.opencaller.core.common.Clock

/** [Clock] whose time only moves when the test says so. */
class FakeClock(
    private var nowMillis: Long = DEFAULT_START_MILLIS,
) : Clock {
    override fun nowMillis(): Long = nowMillis

    /** Sets the current time. */
    fun set(millis: Long) {
        nowMillis = millis
    }

    /** Moves time forward by [millis] (must not be negative). */
    fun advanceBy(millis: Long) {
        require(millis >= 0) { "time can only move forward" }
        nowMillis += millis
    }

    /** Default start time. */
    companion object {
        /** 2026-01-01T00:00:00Z. */
        const val DEFAULT_START_MILLIS = 1_767_225_600_000L
    }
}
