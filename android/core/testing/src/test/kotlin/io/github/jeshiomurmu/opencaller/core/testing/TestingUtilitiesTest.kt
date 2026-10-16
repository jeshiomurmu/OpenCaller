package io.github.jeshiomurmu.opencaller.core.testing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TestingUtilitiesTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun fakeClockMovesOnlyWhenTold() {
        val clock = FakeClock()
        assertEquals(FakeClock.DEFAULT_START_MILLIS, clock.nowMillis())
        clock.advanceBy(1_000)
        assertEquals(FakeClock.DEFAULT_START_MILLIS + 1_000, clock.nowMillis())
        clock.set(5)
        assertEquals(5, clock.nowMillis())
    }

    @Test(expected = IllegalArgumentException::class)
    fun fakeClockRejectsGoingBack() {
        FakeClock().advanceBy(-1)
    }

    @Test
    fun testDispatcherProviderUsesOneDispatcher() {
        val dispatcher = StandardTestDispatcher()
        val provider = TestDispatcherProvider(dispatcher)
        assertSame(dispatcher, provider.main)
        assertSame(dispatcher, provider.io)
        assertSame(dispatcher, provider.default)
    }

    @Test
    fun mainDispatcherRuleReplacesMain() =
        runTest {
            val result = withContext(Dispatchers.Main) { "ran on main" }
            assertEquals("ran on main", result)
        }

    @Test
    fun fixturesAreReadable() {
        val numbers = Fixtures.csv("numbers.csv")
        assertTrue(numbers.size >= 12)
        assertTrue(numbers.all { it.size == 6 })
        assertEquals(setOf("BD", "IN", "US", "GB", "NG", "AE"), numbers.map { it[5] }.toSet())
        assertTrue(Fixtures.csv("bucket_vectors.csv").all { it.size == 4 })
    }
}
