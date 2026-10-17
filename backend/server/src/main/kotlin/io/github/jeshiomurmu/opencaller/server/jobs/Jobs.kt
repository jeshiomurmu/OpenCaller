package io.github.jeshiomurmu.opencaller.server.jobs

import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.toKotlinDuration

/** A background job; [lockKey] is its PostgreSQL advisory-lock key, unique per job. */
class LeaderJob(
    val name: String,
    val lockKey: Long,
    val run: suspend () -> Any,
)

/** Outcome of asking to run a job. */
sealed interface JobOutcome {
    /** This instance ran the job; [result] is what it returned. */
    data class Ran(
        val result: Any,
    ) : JobOutcome

    /** Another instance (or an earlier run here) holds the job's lock, so nothing ran. */
    data object Busy : JobOutcome
}

/**
 * Runs jobs under their advisory lock, so with several server instances each run happens once. Records
 * `opencaller.job.duration` and `opencaller.job.last.success` (epoch seconds; "scoring lag" = now minus that).
 */
class JobRunner(
    private val db: ServerDatabase,
    private val registry: MeterRegistry,
    private val clock: Clock,
) {
    private val lastSuccess = mutableMapOf<String, AtomicLong>()

    /** Runs [job] if this instance gets its lock. Exceptions propagate (the scheduler logs them). */
    suspend fun runIfLeader(job: LeaderJob): JobOutcome {
        val timer = Timer.builder("opencaller.job.duration").tag("job", job.name).register(registry)
        val start = clock.millis()
        val result = db.withAdvisoryLock(job.lockKey) { job.run() } ?: return JobOutcome.Busy
        timer.record(Duration.ofMillis(clock.millis() - start))
        successGauge(job.name).set(clock.instant().epochSecond)
        return JobOutcome.Ran(result)
    }

    @Synchronized
    private fun successGauge(name: String): AtomicLong =
        lastSuccess.getOrPut(name) {
            AtomicLong(0).also { value ->
                Gauge.builder("opencaller.job.last.success", value) { it.get().toDouble() }.tag("job", name).register(registry)
            }
        }
}

/** Starts the periodic jobs in [scope] (cancelled on shutdown). Failures are logged and the job tries again next time. */
class JobScheduler(
    private val scope: CoroutineScope,
    private val runner: JobRunner,
    private val clock: Clock,
) {
    /** Runs [job] now and then every [interval]. */
    fun every(
        interval: Duration,
        job: LeaderJob,
    ): Job =
        scope.launch {
            while (isActive) {
                runSafely(job)
                delay(interval.toKotlinDuration())
            }
        }

    /** Runs [job] every day at [hourUtc]:00 UTC. */
    fun dailyAt(
        hourUtc: Int,
        job: LeaderJob,
    ): Job =
        scope.launch {
            while (isActive) {
                delay(untilNext(hourUtc).toKotlinDuration())
                runSafely(job)
            }
        }

    /** Time from now until the next [hourUtc]:00 UTC. */
    fun untilNext(hourUtc: Int): Duration {
        val now = ZonedDateTime.now(clock).withZoneSameInstant(ZoneOffset.UTC)
        var next = now.with(LocalTime.of(hourUtc, 0))
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    private suspend fun runSafely(job: LeaderJob) {
        try {
            when (val outcome = runner.runIfLeader(job)) {
                is JobOutcome.Ran -> log.info("Job {} finished: {}", job.name, outcome.result)
                JobOutcome.Busy -> log.info("Job {} skipped: another instance is running it", job.name)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // A failed run must not stop the schedule; the next run retries from the saved watermark.
            log.error("Job {} failed", job.name, e)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(JobScheduler::class.java)
    }
}
