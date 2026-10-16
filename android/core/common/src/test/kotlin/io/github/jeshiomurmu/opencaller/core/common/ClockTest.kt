package io.github.jeshiomurmu.opencaller.core.common

import org.junit.Assert.assertTrue
import org.junit.Test

class ClockTest {
    @Test
    fun systemWallClockIsCloseToSystemTime() {
        val before = System.currentTimeMillis()
        val now = SystemWallClock.nowMillis()
        val after = System.currentTimeMillis()
        assertTrue(now in before..after)
    }

    @Test
    fun defaultDispatcherProviderExposesAllDispatchers() {
        val dispatchers = listOf(DefaultDispatcherProvider.io, DefaultDispatcherProvider.default)
        assertTrue(dispatchers.distinct().size == 2)
    }
}
