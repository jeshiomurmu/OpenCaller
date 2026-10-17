package io.github.jeshiomurmu.opencaller.server.jobs

import io.github.jeshiomurmu.opencaller.server.testing.MutableClock
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Leader election with PostgreSQL advisory locks, and the scheduler's timing. */
class JobRunnerTest {
    private val clock = MutableClock(Instant.parse("2026-10-16T12:00:00Z"))
    private val registry = SimpleMeterRegistry()

    @Test
    fun onlyOneInstanceRunsAJobAtATime() =
        runBlocking {
            // Two runners = two server instances sharing the database.
            val a = JobRunner(TestInfra.database, registry, clock)
            val b = JobRunner(TestInfra.database, registry, clock)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val job =
                LeaderJob("test", lockKey = 99_000_001L) {
                    started.complete(Unit)
                    release.await()
                    "done"
                }
            val first = async(Dispatchers.IO) { a.runIfLeader(job) }
            withTimeout(10_000) { started.await() }
            assertEquals(JobOutcome.Busy, b.runIfLeader(job))
            // Another job has its own lock.
            assertIs<JobOutcome.Ran>(b.runIfLeader(LeaderJob("other", lockKey = 99_000_002L) { "ok" }))
            release.complete(Unit)
            assertEquals(JobOutcome.Ran("done"), first.await())
            // Released: the other instance can run it now.
            assertEquals(JobOutcome.Ran("again"), b.runIfLeader(LeaderJob("test", lockKey = 99_000_001L) { "again" }))
            assertEquals(
                clock.now.epochSecond.toDouble(),
                registry
                    .get("opencaller.job.last.success")
                    .tag("job", "test")
                    .gauge()
                    .value(),
            )
        }

    @Test
    fun theLockIsReleasedWhenAJobFails() =
        runBlocking {
            val runner = JobRunner(TestInfra.database, registry, clock)
            runCatching { runner.runIfLeader(LeaderJob("failing", lockKey = 99_000_003L) { error("boom") }) }
            assertEquals(JobOutcome.Ran(1), runner.runIfLeader(LeaderJob("failing", lockKey = 99_000_003L) { 1 }))
        }

    @Test
    fun dailyJobsWaitForTheNextRunHour() {
        val scheduler = JobScheduler(CoroutineScope(Dispatchers.Default), JobRunner(TestInfra.database, registry, clock), clock)
        assertEquals(Duration.ofHours(15), scheduler.untilNext(3))
        assertEquals(Duration.ofHours(1), scheduler.untilNext(13))
        // Exactly at the hour: the next run is tomorrow.
        assertEquals(Duration.ofHours(24), scheduler.untilNext(12))
    }
}
