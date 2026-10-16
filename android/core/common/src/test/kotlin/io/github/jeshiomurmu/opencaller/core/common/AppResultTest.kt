package io.github.jeshiomurmu.opencaller.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppResultTest {
    private val success: AppResult<Int> = AppResult.Success(2)
    private val failure: AppResult<Int> = AppResult.Failure(AppError.NotFound)

    @Test
    fun getOrNullAndErrorOrNull() {
        assertEquals(2, success.getOrNull())
        assertNull(success.errorOrNull())
        assertNull(failure.getOrNull())
        assertEquals(AppError.NotFound, failure.errorOrNull())
    }

    @Test
    fun mapTransformsOnlySuccess() {
        assertEquals(AppResult.Success(4), success.map { it * 2 })
        assertEquals(failure, failure.map { it * 2 })
    }

    @Test
    fun flatMapChainsAndShortCircuits() {
        val toFailure: (Int) -> AppResult<String> = { AppResult.Failure(AppError.Validation("bad $it")) }
        assertEquals(AppResult.Success("2"), success.flatMap { AppResult.Success(it.toString()) })
        assertEquals(AppResult.Failure(AppError.Validation("bad 2")), success.flatMap(toFailure))
        assertEquals(failure, failure.flatMap { AppResult.Success(it.toString()) })
    }

    @Test
    fun foldPicksTheMatchingBranch() {
        assertEquals("ok 2", success.fold({ "ok $it" }, { "err $it" }))
        assertEquals("err NotFound", failure.fold({ "ok $it" }, { "err $it" }))
    }

    @Test
    fun errorsCarryTheirDetails() {
        val cause = IllegalStateException("boom")
        assertEquals(30L, AppError.RateLimited(retryAfterSeconds = 30).retryAfterSeconds)
        assertNull(AppError.RateLimited(retryAfterSeconds = null).retryAfterSeconds)
        assertEquals("too short", AppError.Validation("too short").message)
        assertEquals(cause, AppError.Unexpected(cause).cause)
    }
}
